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

package io.evitadb.core.query.sort.reference.sorter;

import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.ForcedSortResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.sorter.SortResolutionStrategies;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Index route of a {@link PickFirstByEntityProperty} ordering: sorts owner entities by the value of their first row,
 * in target order, that carries the sorted value.
 *
 * The reduced indexes to walk are a function of the selection, so they are resolved here, at execution time, by
 * {@link PickFirstReducedIndexResolver} - never at planning time, when no selection exists yet and the only index
 * sets at hand are the candidates of whatever `referenceHaving` the filter contains.
 *
 * The indexes are then walked in target order and each claims the owners it holds a value for among those not
 * claimed yet - a winner-per-owner projection of "the first row in target order". Each index is asked only about
 * its own unclaimed owners (the intersection of its owner set with the unclaimed rest of the selection), so the walk
 * costs what the claimed rows cost rather than the size of the selection times the number of indexes, which is what
 * merging one provider per index cost: each provider had to report the whole unclaimed rest back as a new bitmap.
 * A claim is pure bitmap work - the positions of the claimed owners in the index's sorted provider, and their removal
 * from the unclaimed rest - no value is read while claiming. Each index is asked in the cheaper of two ways: an
 * unclaimed rest far smaller than the index is resolved against it directly (the not-found result becomes the new
 * rest), otherwise the index is first intersected with the rest. An index without a sort index of the value is
 * skipped before any bitmap is touched, and the provider of an index is built only once the index has shown it holds
 * an unclaimed owner.
 *
 * The positions one index claimed, walked in ascending order, are already in the order of value in the ordering
 * direction and then of owner primary key in the same direction (the descending provider is the exact mirror of the
 * ascending one), so each index yields one sorted run. The runs are merged lazily by their head values into one
 * sorted provider for the whole reference, built per query; the merge reads values only for the owners it passes
 * and stops at the end of the requested page, so ordering a large selection costs its claims plus the page, never
 * a value read for every selected owner.
 *
 * # Virtual partitions
 *
 * A registered single-owner partition read virtually ({@link VirtualReferencePartition}) has no sort index of its own -
 * its value lives in the registry of its referenced-type entity index. It takes part in the walk at its place in
 * target order like any other partition, but it is admitted only when its owner is still in the unclaimed rest - the
 * very bitmap the materialized claims start from, initialized to the selection - so an owner outside the selection
 * (the gather may hand over partitions of unselected owners) or one already claimed is rejected before any value is
 * read. Only then is the value id of its row read (`O(log n)`); a row without a value claims nothing and the owner
 * stays unclaimed for the partitions that follow, a row with one adds the owner to the group of its value id and
 * removes it from the unclaimed rest.
 *
 * The claimed groups of one registry (one per scope) form one more sorted run, ordered by the values of the groups:
 * for `g` groups out of `D` distinct values of the registry either by sorting the `g` values, `O(g log g)`, or - when
 * that would cost more than `D` - by one walk over the registry's values in order, `O(D)`, stopped at the last claimed
 * one. Owners of one group follow in primary key order in the ordering direction, which is the order a sort index
 * keeps for one value, and the run hands the merge the same normalized values the seekers of the materialized runs
 * emit. No family value that no selected owner claimed is ever read, and no sort index of the registry is walked for a
 * sparse claim.
 *
 * Owners with no row carrying the value stay unsorted and fall through to the next sorter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PickFirstReferenceSorter implements Sorter {
	/**
	 * How many times larger than the unclaimed rest an index must be for the rest to be resolved against it directly.
	 * A direct resolution looks each unclaimed owner up in the index (a binary search, about `log2(index size)`
	 * comparisons), a linear intersection touches every owner of both sides once, so the direct path only pays off
	 * when the rest is a small fraction of the index - taken at comparable sizes, it looked a moderate selection up
	 * in every large residual index of a narrowed query and cost several times the intersection.
	 */
	private static final int DIRECT_RESOLUTION_RATIO = 16;
	/**
	 * Resolves the reduced indexes that may hold a row of the selection, in target order.
	 */
	@Nonnull private final PickFirstReducedIndexResolver indexResolver;
	/**
	 * Returns the source of the provider of sorted values of one partition, or `null` when the index holds none.
	 * Finding out whether an index holds values is cheap; building its provider is not (it creates the value seeker
	 * eagerly), so the provider is built only once the index has shown it holds an unclaimed owner.
	 */
	@Nonnull private final Function<ReferencePartition, Supplier<SortedRecordsProvider>> providerFactory;
	/**
	 * Reads the sorted values of virtual partitions out of their registry.
	 */
	@Nonnull private final PickFirstVirtualSortValues virtualValues;
	/**
	 * Comparator of the provided values, already in the ordering direction.
	 */
	@SuppressWarnings("rawtypes")
	@Nonnull private final Comparator comparator;
	/**
	 * Direction of the ordering, applied to the primary keys of owners with equal values.
	 */
	@Nonnull private final OrderDirection primaryKeyOrder;

	/**
	 * Creates the sorter.
	 *
	 * @param indexResolver   resolves the partitions that may hold a row of the selection
	 * @param providerFactory returns the source of the provider of sorted values of one materialized partition, or
	 *                        `null` when the index holds no value - it is never handed a virtual partition
	 * @param virtualValues   reads the sorted values of virtual partitions out of their registry
	 * @param comparator      comparator of the provided values in the ordering direction
	 * @param primaryKeyOrder direction of the ordering, applied to the primary keys of owners with equal values
	 */
	public PickFirstReferenceSorter(
		@Nonnull PickFirstReducedIndexResolver indexResolver,
		@Nonnull Function<ReferencePartition, Supplier<SortedRecordsProvider>> providerFactory,
		@Nonnull PickFirstVirtualSortValues virtualValues,
		@SuppressWarnings("rawtypes") @Nonnull Comparator comparator,
		@Nonnull OrderDirection primaryKeyOrder
	) {
		this.indexResolver = indexResolver;
		this.providerFactory = providerFactory;
		this.virtualValues = virtualValues;
		this.comparator = comparator;
		this.primaryKeyOrder = primaryKeyOrder;
	}

	/**
	 * Decides how the virtual run of `groupCount` claimed value groups out of `distinctValueCount` distinct values of
	 * one registry is ordered: by sorting the claimed values, `O(g log g)`, unless that costs more than one walk over
	 * all the values of the registry, `O(D)`.
	 *
	 * @param groupCount         the number of claimed value groups `g`, positive
	 * @param distinctValueCount the number of distinct values of the registry `D`
	 * @return TRUE when the run is ordered by the walk over the registry's values
	 */
	static boolean isDense(int groupCount, int distinctValueCount) {
		// ceil(log2(g)) - zero for a single group, which therefore never walks
		final int log2 = 32 - Integer.numberOfLeadingZeros(groupCount - 1);
		return (long) groupCount * log2 > distinctValueCount;
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		final Bitmap selection = sortingContext.nonSortedKeys();
		if (queryContext.getPrefetchedEntities() != null || selection.isEmpty()) {
			return sortingContext;
		}
		final ReferencePartition[] indexes = this.indexResolver.resolve(selection).indexes();
		final Claims claims = claim(queryContext, selection, indexes);
		if (claims.runCount == 0 && claims.virtualGroupCount == 0) {
			return sortingContext;
		}

		final int startIndex = sortingContext.recomputedStartIndex();
		final int endIndex = sortingContext.recomputedEndIndex();
		final int peak = sortingContext.peak();
		final int toRead = Math.min(endIndex - startIndex, result.length - peak);
		int alreadyRead = 0;
		int toSkip = startIndex;
		final boolean descending = this.primaryKeyOrder == OrderDirection.DESC;
		final RunMerge merge = new RunMerge(
			claims, createVirtualRuns(claims, descending), this.comparator, descending
		);
		while ((toRead > alreadyRead || toSkip > 0) && merge.hasNext()) {
			final int recordId = merge.next();
			if (toSkip > 0) {
				toSkip--;
				if (skippedRecordsConsumer != null) {
					skippedRecordsConsumer.accept(recordId);
				}
			} else {
				result[peak + alreadyRead++] = recordId;
			}
		}
		return sortingContext.createResultContext(
			claims.unclaimed.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(claims.unclaimed),
			alreadyRead,
			startIndex - toSkip
		);
	}

	/**
	 * Walks the indexes in target order and lets each claim the unclaimed owners it holds a value for. Claiming reads
	 * no value: an index contributes the positions of its claimed owners in its sorted provider, and the owners leave
	 * the unclaimed rest in one bitmap operation.
	 *
	 * @param queryContext the context of the executed query
	 * @param selection    the owners being sorted
	 * A virtual partition claims its owner only when the owner is still in the unclaimed rest - the bitmap initialized
	 * to the selection that the materialized claims shrink too - and its row carries a value; see the class
	 * documentation.
	 *
	 * @param indexes      the partitions that may hold a row of the selection, in target order
	 * @return one sorted run per index that claimed an owner, the claimed value groups of the virtual partitions, and
	 *         the owners nothing claimed
	 */
	@Nonnull
	private Claims claim(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Bitmap selection,
		@Nonnull ReferencePartition[] indexes
	) {
		// the working copy is copy-on-write, so removing claimed owners never touches the selection itself
		PersistentRoaringBitmap unclaimed = RoaringBitmapBackedBitmap.getRoaringBitmap(selection).clone();
		int unclaimedCount = unclaimed.getCardinality();
		final Claims claims = new Claims();
		final int[] bufferA = queryContext.borrowBuffer();
		final int[] bufferB = queryContext.borrowBuffer();
		// debug override (or null for cost-based) + per-strategy telemetry tally (null when telemetry is off)
		final ForcedSortResolution forcedResolution = SortResolutionStrategies.resolveForcedResolution(queryContext);
		final int[] strategyTally = SortResolutionStrategies.newStrategyTally(queryContext);
		try {
			for (ReferencePartition partition : indexes) {
				if (unclaimedCount == 0) {
					break;
				}
				if (partition instanceof VirtualReferencePartition virtual) {
					final int owner = virtual.ownerPrimaryKey();
					// admitted only through the unclaimed rest: an unselected owner the gather handed over and an owner
					// already claimed are both rejected here, before any value is read
					if (unclaimed.contains(owner)) {
						final int valueId = this.virtualValues.getValueId(virtual);
						// a row without the value claims nothing - a later row of the owner may still carry one
						if (valueId != PickFirstVirtualSortValues.NO_VALUE) {
							claims.claimVirtual(virtual.typeIndex(), valueId, owner);
							unclaimed.remove(owner);
							unclaimedCount--;
						}
					}
					continue;
				}
				final ReferencePartition index = partition;
				// an index without a sort index of the value holds nothing to claim - ask for it before touching any
				// bitmap, it is the whole cost of an ordering by a value few rows carry
				final Supplier<SortedRecordsProvider> providerSource = this.providerFactory.apply(index);
				if (providerSource == null) {
					continue;
				}
				final Bitmap indexOwners = index.getAllPrimaryKeys();
				final PersistentRoaringBitmap indexOwnerBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(indexOwners);
				// most indexes a gather hands over (the residual ones above all) hold no unclaimed owner, and this check
				// tells so without allocating the intersection or building the provider
				if (!PersistentRoaringBitmap.intersects(indexOwnerBitmap, unclaimed)) {
					continue;
				}
				final SortedRecordsProvider provider = providerSource.get();
				if ((long) unclaimedCount * DIRECT_RESOLUTION_RATIO <= indexOwners.size()) {
					// the unclaimed rest is far smaller than the index: resolve it directly - a lookup per unclaimed
					// owner - and what the provider does not hold is exactly the new unclaimed rest, with no
					// intersection and no removal
					final PositionResolution resolution = provider.resolvePositions(
						unclaimed, unclaimedCount, bufferA, bufferB, forcedResolution
					);
					SortResolutionStrategies.tally(strategyTally, resolution);
					if (!resolution.mask().isEmpty()) {
						claims.addRun(provider, resolution.mask());
						unclaimed = resolution.notFoundRecords();
						unclaimedCount = resolution.notFoundRecordsCount();
					}
				} else {
					// comparable sizes, or the index is the smaller side: resolving the whole rest would look every
					// unclaimed owner up in the index and copy the rest into the not-found result once per index, so
					// the two are intersected linearly and only the owners the index holds are resolved and removed
					final PersistentRoaringBitmap candidates = PersistentRoaringBitmap.and(indexOwnerBitmap, unclaimed);
					final PositionResolution resolution = provider.resolvePositions(
						candidates, candidates.getCardinality(), bufferA, bufferB, forcedResolution
					);
					SortResolutionStrategies.tally(strategyTally, resolution);
					if (!resolution.mask().isEmpty()) {
						claims.addRun(provider, resolution.mask());
						final PersistentRoaringBitmap claimed = resolution.notFoundRecordsCount() == 0 ?
							candidates :
							PersistentRoaringBitmap.andNot(candidates, resolution.notFoundRecords());
						unclaimed.andNot(claimed);
						unclaimedCount -= claimed.getCardinality();
					}
				}
			}
			claims.unclaimed = unclaimed;
			return claims;
		} finally {
			SortResolutionStrategies.report(queryContext, strategyTally);
			queryContext.returnBuffer(bufferA);
			queryContext.returnBuffer(bufferB);
		}
	}

	/**
	 * Orders the claimed value groups of every registry into one virtual run - see the class documentation for the
	 * choice between sorting the claimed values and walking the registry's values.
	 *
	 * @param claims     the claims of the walk
	 * @param descending whether the ordering is descending
	 * @return one run per registry whose partitions claimed an owner, possibly none
	 */
	@Nonnull
	private VirtualRun[] createVirtualRuns(@Nonnull Claims claims, boolean descending) {
		final VirtualRun[] runs = new VirtualRun[claims.virtualRegistryCount];
		for (int i = 0; i < claims.virtualRegistryCount; i++) {
			final ReferencedTypeEntityIndex typeIndex = claims.virtualTypeIndexes[i];
			final IntObjectHashMap<PersistentRoaringBitmap> groups = claims.virtualGroups[i];
			final int groupCount = groups.size();
			final VirtualRunBuilder builder = new VirtualRunBuilder(groupCount, this.comparator);
			if (isDense(groupCount, this.virtualValues.getDistinctValueCount(typeIndex))) {
				// one walk over the values of the registry in order, stopped at the last claimed one
				this.virtualValues.forEachValueId(
					typeIndex, descending,
					valueId -> {
						final PersistentRoaringBitmap owners = groups.get(valueId);
						if (owners != null) {
							builder.append(this.virtualValues.getValue(typeIndex, valueId), owners);
						}
						return builder.size() < groupCount;
					}
				);
				Assert.isPremiseValid(
					builder.size() == groupCount,
					() -> "The values of the registry of `" + typeIndex.getIndexKey() + "` lack " +
						(groupCount - builder.size()) + " claimed value ids!"
				);
			} else {
				// sort the claimed values only
				final int[] valueIds = groups.keys().toArray();
				final Serializable[] values = new Serializable[valueIds.length];
				final Integer[] order = new Integer[valueIds.length];
				for (int j = 0; j < valueIds.length; j++) {
					values[j] = this.virtualValues.getValue(typeIndex, valueIds[j]);
					order[j] = j;
				}
				//noinspection unchecked
				Arrays.sort(order, (a, b) -> this.comparator.compare(values[a], values[b]));
				for (int j : order) {
					builder.append(values[j], groups.get(valueIds[j]));
				}
			}
			runs[i] = builder.build(descending);
		}
		return runs;
	}

	/**
	 * Collects the value groups of one virtual run in the order of their values; a group whose value the comparator
	 * finds equal to the previous one is merged into it, so owners sharing an ordering position follow in primary key
	 * order as they do within a sort index.
	 */
	private static final class VirtualRunBuilder {
		/**
		 * Comparator of the values in the ordering direction.
		 */
		@SuppressWarnings("rawtypes")
		@Nonnull private final Comparator comparator;
		/**
		 * The values of the groups, in order.
		 */
		@Nonnull private final Serializable[] values;
		/**
		 * The owners of the groups, in order.
		 */
		@Nonnull private final PersistentRoaringBitmap[] owners;
		/**
		 * The number of claimed groups appended so far.
		 */
		private int appended;
		/**
		 * The number of groups after merging equal values.
		 */
		private int groupCount;

		VirtualRunBuilder(int capacity, @SuppressWarnings("rawtypes") @Nonnull Comparator comparator) {
			this.comparator = comparator;
			this.values = new Serializable[capacity];
			this.owners = new PersistentRoaringBitmap[capacity];
		}

		/**
		 * Appends the next group in value order.
		 *
		 * @param value  the normalized value of the group
		 * @param owners the owners claimed with the value, never empty
		 */
		void append(@Nonnull Serializable value, @Nonnull PersistentRoaringBitmap owners) {
			//noinspection unchecked
			if (this.groupCount > 0 && this.comparator.compare(this.values[this.groupCount - 1], value) == 0) {
				this.owners[this.groupCount - 1] = PersistentRoaringBitmap.or(this.owners[this.groupCount - 1], owners);
			} else {
				this.values[this.groupCount] = value;
				this.owners[this.groupCount] = owners;
				this.groupCount++;
			}
			this.appended++;
		}

		/**
		 * Returns the number of claimed groups appended so far, before merging equal values.
		 *
		 * @return the number of appended groups
		 */
		int size() {
			return this.appended;
		}

		/**
		 * Creates the run.
		 *
		 * @param descending whether owners of one group follow in descending primary key order
		 * @return the run
		 */
		@Nonnull
		VirtualRun build(boolean descending) {
			return new VirtualRun(this.values, this.owners, this.groupCount, descending);
		}
	}

	/**
	 * The sorted run of the virtual partitions of one registry: the claimed value groups in value order, the owners of
	 * one group in primary key order in the ordering direction.
	 */
	private static final class VirtualRun {
		/**
		 * The values of the groups, in order.
		 */
		@Nonnull private final Serializable[] values;
		/**
		 * The owners of the groups, in order.
		 */
		@Nonnull private final PersistentRoaringBitmap[] owners;
		/**
		 * The number of groups.
		 */
		private final int groupCount;
		/**
		 * Whether the owners of one group follow in descending primary key order.
		 */
		private final boolean descending;
		/**
		 * The group the run currently emits.
		 */
		private int group = -1;
		/**
		 * The remaining owners of the current group, `null` before the first one.
		 */
		@Nullable private PeekableIntIterator ownerIt;

		VirtualRun(
			@Nonnull Serializable[] values,
			@Nonnull PersistentRoaringBitmap[] owners,
			int groupCount,
			boolean descending
		) {
			this.values = values;
			this.owners = owners;
			this.groupCount = groupCount;
			this.descending = descending;
		}

		/**
		 * Returns true when an owner remains.
		 *
		 * @return true if another owner follows
		 */
		boolean hasNext() {
			return (this.ownerIt != null && this.ownerIt.hasNext()) || this.group + 1 < this.groupCount;
		}

		/**
		 * Moves to the next owner, which must exist, and returns it; {@link #value()} then returns its value.
		 *
		 * @return the owner primary key
		 */
		int nextOwner() {
			if (this.ownerIt == null || !this.ownerIt.hasNext()) {
				this.group++;
				final PersistentRoaringBitmap groupOwners = this.owners[this.group];
				this.ownerIt = this.descending ? groupOwners.getReverseIntIterator() : groupOwners.getIntIterator();
			}
			return this.ownerIt.next();
		}

		/**
		 * Returns the value of the owner {@link #nextOwner()} returned last.
		 *
		 * @return the normalized value
		 */
		@Nonnull
		Serializable value() {
			return this.values[this.group];
		}
	}

	/**
	 * The sorted runs the walk produced - per claiming index its provider and the positions of the owners it claimed
	 * in that provider - and the owners left unclaimed.
	 */
	private static final class Claims {
		/**
		 * The owners left unclaimed, set when the walk ends.
		 */
		@Nonnull PersistentRoaringBitmap unclaimed = new PersistentRoaringBitmap();
		/**
		 * The provider of each run.
		 */
		@Nonnull SortedRecordsProvider[] providers = new SortedRecordsProvider[16];
		/**
		 * The positions of the claimed owners of each run in its provider.
		 */
		@Nonnull PersistentRoaringBitmap[] masks = new PersistentRoaringBitmap[16];
		/**
		 * The number of runs.
		 */
		int runCount;
		/**
		 * The referenced-type entity indexes whose virtual partitions claimed an owner, one per registry (scope).
		 */
		@Nonnull ReferencedTypeEntityIndex[] virtualTypeIndexes = new ReferencedTypeEntityIndex[2];
		/**
		 * The owners the virtual partitions of each entry of {@link #virtualTypeIndexes} claimed, by value id.
		 */
		@SuppressWarnings("unchecked")
		@Nonnull IntObjectHashMap<PersistentRoaringBitmap>[] virtualGroups = new IntObjectHashMap[2];
		/**
		 * The number of used entries of {@link #virtualTypeIndexes} and {@link #virtualGroups}.
		 */
		int virtualRegistryCount;
		/**
		 * The number of claimed value groups across all registries.
		 */
		int virtualGroupCount;

		/**
		 * Records the claim of a virtual partition: adds the owner to the group of the value id in the registry of the
		 * type index.
		 *
		 * @param typeIndex the referenced-type entity index the value id is scoped to
		 * @param valueId   the value id of the partition's row
		 * @param owner     the owner claimed
		 */
		void claimVirtual(@Nonnull ReferencedTypeEntityIndex typeIndex, int valueId, int owner) {
			IntObjectHashMap<PersistentRoaringBitmap> groups = null;
			for (int i = 0; i < this.virtualRegistryCount; i++) {
				if (this.virtualTypeIndexes[i] == typeIndex) {
					groups = this.virtualGroups[i];
					break;
				}
			}
			if (groups == null) {
				if (this.virtualRegistryCount == this.virtualTypeIndexes.length) {
					this.virtualTypeIndexes = Arrays.copyOf(this.virtualTypeIndexes, this.virtualRegistryCount * 2);
					this.virtualGroups = Arrays.copyOf(this.virtualGroups, this.virtualRegistryCount * 2);
				}
				groups = new IntObjectHashMap<>(16);
				this.virtualTypeIndexes[this.virtualRegistryCount] = typeIndex;
				this.virtualGroups[this.virtualRegistryCount] = groups;
				this.virtualRegistryCount++;
			}
			PersistentRoaringBitmap owners = groups.get(valueId);
			if (owners == null) {
				owners = new PersistentRoaringBitmap();
				groups.put(valueId, owners);
				this.virtualGroupCount++;
			}
			owners.add(owner);
		}

		/**
		 * Records the run of one index.
		 *
		 * @param provider the sorted provider of the index
		 * @param mask     the positions of the owners the index claimed, never empty
		 */
		void addRun(@Nonnull SortedRecordsProvider provider, @Nonnull PersistentRoaringBitmap mask) {
			if (this.runCount == this.providers.length) {
				this.providers = Arrays.copyOf(this.providers, this.runCount * 2);
				this.masks = Arrays.copyOf(this.masks, this.runCount * 2);
			}
			this.providers[this.runCount] = provider;
			this.masks[this.runCount] = mask;
			this.runCount++;
		}
	}

	/**
	 * Lazy k-way merge of the runs: a binary min-heap of runs keyed by their head claim, by value in the ordering
	 * direction and then by owner primary key in the same direction. Only the head of each run has its value read, so
	 * the work done is proportional to the owners actually returned (plus one head per run). Runs of materialized
	 * partitions come first and are numbered from zero, the virtual runs follow them.
	 */
	private static final class RunMerge {
		/**
		 * The provider of each run.
		 */
		@Nonnull private final SortedRecordsProvider[] providers;
		/**
		 * The virtual runs, run `materializedRunCount + i` being `virtualRuns[i]`.
		 */
		@Nonnull private final VirtualRun[] virtualRuns;
		/**
		 * The number of runs of materialized partitions.
		 */
		private final int materializedRunCount;
		/**
		 * Iterator over the remaining positions of each run, ascending.
		 */
		@Nonnull private final PeekableIntIterator[] positions;
		/**
		 * Value seeker of each run, fed ascending positions.
		 */
		@Nonnull private final SortedComparableForwardSeeker[] seekers;
		/**
		 * Owner primary key at the head of each run.
		 */
		@Nonnull private final int[] headOwners;
		/**
		 * Value of the owner at the head of each run.
		 */
		@Nonnull private final Serializable[] headValues;
		/**
		 * Run identifiers organized as a binary min-heap over their heads.
		 */
		@Nonnull private final int[] heap;
		/**
		 * Comparator of the values in the ordering direction.
		 */
		@SuppressWarnings("rawtypes")
		@Nonnull private final Comparator comparator;
		/**
		 * Whether owners with equal values follow in descending primary key order.
		 */
		private final boolean primaryKeysDescending;
		/**
		 * The number of live runs in {@link #heap}.
		 */
		private int heapSize;

		/**
		 * Positions every run at its first claim and builds the heap.
		 *
		 * @param claims                the runs of materialized partitions to merge
		 * @param virtualRuns           the virtual runs to merge, each non-empty
		 * @param comparator            comparator of the values in the ordering direction
		 * @param primaryKeysDescending whether owners with equal values follow in descending primary key order
		 */
		RunMerge(
			@Nonnull Claims claims,
			@Nonnull VirtualRun[] virtualRuns,
			@SuppressWarnings("rawtypes") @Nonnull Comparator comparator,
			boolean primaryKeysDescending
		) {
			this.materializedRunCount = claims.runCount;
			this.virtualRuns = virtualRuns;
			final int runCount = claims.runCount + virtualRuns.length;
			this.providers = claims.providers;
			this.positions = new PeekableIntIterator[runCount];
			this.seekers = new SortedComparableForwardSeeker[runCount];
			this.headOwners = new int[runCount];
			this.headValues = new Serializable[runCount];
			this.heap = new int[runCount];
			this.comparator = comparator;
			this.primaryKeysDescending = primaryKeysDescending;
			for (int run = 0; run < runCount; run++) {
				if (run < this.materializedRunCount) {
					this.positions[run] = claims.masks[run].getIntIterator();
					final SortedComparableForwardSeeker seeker =
						this.providers[run].getSortedComparableForwardSeeker();
					seeker.reset();
					this.seekers[run] = seeker;
				}
				// every run is non-empty, so each has a head
				advance(run);
				this.heap[run] = run;
			}
			this.heapSize = runCount;
			for (int slot = this.heapSize / 2 - 1; slot >= 0; slot--) {
				siftDown(slot);
			}
		}

		/**
		 * Returns true when an owner remains to be returned.
		 *
		 * @return true if any run still has a head
		 */
		boolean hasNext() {
			return this.heapSize > 0;
		}

		/**
		 * Returns the next owner in the merged order and advances its run.
		 *
		 * @return the owner primary key
		 */
		int next() {
			final int run = this.heap[0];
			final int owner = this.headOwners[run];
			if (hasNext(run)) {
				advance(run);
			} else {
				this.heap[0] = this.heap[--this.heapSize];
			}
			if (this.heapSize > 0) {
				siftDown(0);
			}
			return owner;
		}

		/**
		 * Returns true when the run has another claim after its head.
		 *
		 * @param run the run
		 * @return true if the run can advance
		 */
		private boolean hasNext(int run) {
			return run < this.materializedRunCount ?
				this.positions[run].hasNext() : this.virtualRuns[run - this.materializedRunCount].hasNext();
		}

		/**
		 * Moves the head of the run to its next claim, which must exist.
		 *
		 * @param run the run to advance
		 */
		private void advance(int run) {
			if (run < this.materializedRunCount) {
				final int position = this.positions[run].next();
				this.headOwners[run] = this.providers[run].recordAt(position);
				this.headValues[run] = this.seekers[run].getValueToCompareOn(position);
			} else {
				final VirtualRun virtualRun = this.virtualRuns[run - this.materializedRunCount];
				this.headOwners[run] = virtualRun.nextOwner();
				this.headValues[run] = virtualRun.value();
			}
		}

		/**
		 * Compares the heads of two runs.
		 *
		 * @param runA the first run
		 * @param runB the second run
		 * @return negative when the head of `runA` goes first
		 */
		private int compareHeads(int runA, int runB) {
			//noinspection unchecked
			final int result = this.comparator.compare(this.headValues[runA], this.headValues[runB]);
			if (result != 0) {
				return result;
			}
			return this.primaryKeysDescending ?
				Integer.compare(this.headOwners[runB], this.headOwners[runA]) :
				Integer.compare(this.headOwners[runA], this.headOwners[runB]);
		}

		/**
		 * Restores the heap property below the given slot.
		 *
		 * @param slot the slot whose run may be greater than its children
		 */
		private void siftDown(int slot) {
			int current = slot;
			while (true) {
				final int left = 2 * current + 1;
				if (left >= this.heapSize) {
					return;
				}
				final int right = left + 1;
				int smallest = left;
				if (right < this.heapSize && compareHeads(this.heap[right], this.heap[left]) < 0) {
					smallest = right;
				}
				if (compareHeads(this.heap[smallest], this.heap[current]) >= 0) {
					return;
				}
				final int swap = this.heap[current];
				this.heap[current] = this.heap[smallest];
				this.heap[smallest] = swap;
				current = smallest;
			}
		}
	}

}
