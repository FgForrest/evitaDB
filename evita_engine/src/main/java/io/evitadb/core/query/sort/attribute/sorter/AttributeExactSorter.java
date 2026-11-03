/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.sort.attribute.sorter;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectIntMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.reference.sorter.VirtualPartitionSortedRecords;
import io.evitadb.dataType.Scope;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.ArrayUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static io.evitadb.core.query.sort.EntityReferenceSensitiveComparator.getFirstValuedReference;

/**
 * AttributeExactSorter sorter outputs filtered results in an order of the attribute values in {@link #exactOrder}.
 * Within a traverse each block chooses the first valued representative row of each owner before testing listed
 * values. Unlisted and missing values are handed to the next child sorter or block. Equal listed values keep
 * ascending owner primary key order, as on the entity exact route.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeExactSorter implements Sorter {
	/**
	 * Name of the attribute the sorter sorts along.
	 */
	private final String attributeName;
	/**
	 * Translates an attribute value to the primary keys of the entities carrying it - the lookup of a {@link SortIndex}
	 * for the indexed entities and materialized reference partitions, of the registry row for a virtual reference
	 * partition.
	 */
	@Nullable private final Function<Serializable, Bitmap> recordsEqualTo;
	/**
	 * The attribute values whose order must be maintained in the sorted result.
	 */
	private final Serializable[] exactOrder;
	/**
	 * The scope of the sort data lookup - only its entities are sorted, on the prefetch route as on the index route.
	 */
	@Nullable private final Scope scope;
	/**
	 * Reference row selection and block-local index lookups, present only for a traverse ordering.
	 */
	@Nullable private final TraverseOrdering traverseOrdering;

	/**
	 * Creates the sorter.
	 *
	 * @param attributeName  the name of the attribute the sorter sorts along
	 * @param exactOrder     the attribute values whose order must be maintained in the sorted result
	 * @param recordsEqualTo translates an attribute value to the primary keys of the entities carrying it
	 * @param scope          the scope whose entities alone the sorter sorts
	 */
	public AttributeExactSorter(
		@Nonnull String attributeName,
		@Nonnull Serializable[] exactOrder,
		@Nonnull Function<Serializable, Bitmap> recordsEqualTo,
		@Nonnull Scope scope
	) {
		this.attributeName = attributeName;
		this.exactOrder = exactOrder;
		this.recordsEqualTo = recordsEqualTo;
		this.scope = scope;
		this.traverseOrdering = null;
	}

	/**
	 * Creates an exact sorter for traversal blocks. Only partition metadata is grouped at planning time; attribute
	 * lookups and valued-owner masks are resolved for the currently visited block at execution time.
	 *
	 * @param attributeName   the sorted reference attribute
	 * @param exactOrder      the listed values in their requested order
	 * @param referenceSchema the ordered reference
	 * @param attributeSchema the sorted attribute schema
	 * @param locale          the query locale
	 * @param partitions      the partitions in representative key and scope order within each target
	 */
	public AttributeExactSorter(
		@Nonnull String attributeName,
		@Nonnull Serializable[] exactOrder,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale,
		@Nonnull ReferencePartition[] partitions
	) {
		this.attributeName = attributeName;
		this.exactOrder = exactOrder;
		this.recordsEqualTo = null;
		this.scope = null;
		final Locale attributeLocale = attributeSchema.isLocalized() ? locale : null;
		final Function<ReferenceContract, Serializable> attributeExtractor = attributeLocale == null ?
			reference -> reference.getAttribute(attributeName) :
			reference -> reference.getAttribute(attributeName, attributeLocale);
		this.traverseOrdering = new TraverseOrdering(
			referenceSchema, attributeSchema, attributeLocale, attributeExtractor,
			SortIndex.createNormalizerFor(
				new ComparatorSource(attributeSchema.getPlainType(), OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				attributeSchema.getIndexedDecimalPlaces()
			).orElseGet(UnaryOperator::identity),
			Arrays.stream(partitions).collect(
				Collectors.groupingBy(partition -> partition.getReferenceKey().primaryKey())
			)
		);
	}

	/**
	 * Sorts and slices the current selection through its index lookup or prefetched representing rows.
	 *
	 * @param sortingContext         the current selection and optional traversal block key
	 * @param result                 receives the requested page
	 * @param skippedRecordsConsumer receives owners skipped before the page
	 * @return the remaining selection and updated page counters
	 */
	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		if (sortingContext.queryContext().getPrefetchedEntities() == null) {
			return sortOutputBasedOnIndex(sortingContext, result, skippedRecordsConsumer);
		} else {
			return sortOutputByPrefetchedEntities(sortingContext, result, skippedRecordsConsumer);
		}
	}

	/**
	 * Sorts the selected primary key ids by the order of attributes in {@link SortIndex}.
	 */
	@Nonnull
	private SortingContext sortOutputBasedOnIndex(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final int recomputedStartIndex = sortingContext.recomputedStartIndex();
		final int recomputedEndIndex = sortingContext.recomputedEndIndex();

		final int[] entireResult = sortingContext.nonSortedKeys().getArray();
		final int length = Math.min(entireResult.length, recomputedEndIndex - recomputedStartIndex);
		if (length < 0) {
			throw new IndexOutOfBoundsException("Index: " + recomputedStartIndex + ", Size: " + entireResult.length);
		}

		// retrieve primary keys in listed-value order, using the index or the current traversal block's lookup
		final Function<Serializable, Bitmap> lookup = this.traverseOrdering == null ?
			Objects.requireNonNull(this.recordsEqualTo) :
			this.traverseOrdering.createLookup(Objects.requireNonNull(sortingContext.referenceKey()));
		final int[] exactPkOrder = Arrays.stream(this.exactOrder)
			.map(lookup)
			.flatMapToInt(Bitmap::stream)
			.toArray();

		// now sort the real result by the exactPkOrder
		final int lastSortedItem = ArrayUtils.sortAlong(exactPkOrder, entireResult);

		// only the records matched by the exact order (positions `[0, lastSortedItem)`) are sorted by this sorter -
		// when the page starts past them, none is appended and only the matched ones count as skipped, so that
		// the next sorter skips the rest of the offset from the records it receives
		final int skippedRecords = Math.min(recomputedStartIndex, lastSortedItem);
		final int toAppend = Math.max(0, Math.min(lastSortedItem, recomputedEndIndex) - recomputedStartIndex);
		if (skippedRecordsConsumer != null) {
			for (int i = 0; i < skippedRecords; i++) {
				skippedRecordsConsumer.accept(entireResult[i]);
			}
		}
		if (toAppend > 0) {
			System.arraycopy(entireResult, recomputedStartIndex, result, sortingContext.peak(), toAppend);
		}

		// if there are no more records to sort or no additional sorter is present, return entire result
		if (lastSortedItem == entireResult.length) {
			return sortingContext.createResultContext(
				EmptyBitmap.INSTANCE,
				toAppend,
				skippedRecords
			);
		} else {
			// otherwise, collect the not sorted record ids
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (int i = lastSortedItem; i < entireResult.length; i++) {
				writer.add(entireResult[i]);
			}
			// pass them to another sorter
			final PersistentRoaringBitmap outputBitmap = writer.get();
			return sortingContext.createResultContext(
				outputBitmap.isEmpty() ?
					EmptyBitmap.INSTANCE : new BaseBitmap(outputBitmap),
				toAppend,
				skippedRecords
			);
		}
	}

	/**
	 * Sorts the selected primary key ids by the order of attributes retrieved from prefetched entities and compared
	 * by comparator in the place.
	 */
	@Nonnull
	private SortingContext sortOutputByPrefetchedEntities(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final int recomputedStartIndex = sortingContext.recomputedStartIndex();
		final int recomputedEndIndex = sortingContext.recomputedEndIndex();
		final int peak = sortingContext.peak();
		final QueryExecutionContext queryContext = sortingContext.queryContext();

		// classify every entity once - only the entities whose value is listed are sorted, the others are passed on
		// to the next sorter and never compared, so that even a lone one is recognized
		final Bitmap nonSortedKeys = sortingContext.nonSortedKeys();
		final AttributePositionComparator entityComparator;
		if (this.traverseOrdering == null) {
			entityComparator = new AttributePositionComparator(
				entity -> entity.getAttribute(this.attributeName), this.exactOrder,
				EnumSet.of(Objects.requireNonNull(this.scope))
			);
		} else {
			final RepresentativeReferenceKey blockKey = Objects.requireNonNull(sortingContext.referenceKey());
			entityComparator = new AttributePositionComparator(
				entity -> this.traverseOrdering.getValue(entity, blockKey),
				Arrays.stream(this.exactOrder).map(this.traverseOrdering.normalizer()).toArray(Serializable[]::new),
				this.traverseOrdering.getScopes(blockKey)
			);
		}
		entityComparator.prepareFor(nonSortedKeys.size());
		final List<EntityContract> sortedEntities = new ArrayList<>(nonSortedKeys.size());
		final PersistentRoaringBitmap notFoundRecords = new PersistentRoaringBitmap();
		final OfInt it = nonSortedKeys.iterator();
		while (it.hasNext()) {
			final int id = it.nextInt();
			final EntityContract entity = queryContext.translateToEntity(id);
			if (entityComparator.canSort(entity)) {
				sortedEntities.add(entity);
			} else {
				notFoundRecords.add(id);
			}
		}
		sortedEntities.sort(entityComparator);

		// skip and copy the requested slice of the sorted entities
		final int sortedCount = sortedEntities.size();
		final int skippedRecords = Math.min(recomputedStartIndex, sortedCount);
		if (skippedRecordsConsumer != null) {
			for (int i = 0; i < skippedRecords; i++) {
				skippedRecordsConsumer.accept(queryContext.translateEntity(sortedEntities.get(i)));
			}
		}
		final int appendedRecords = Math.max(
			0, Math.min(Math.min(recomputedEndIndex, sortedCount) - skippedRecords, result.length - peak)
		);
		for (int i = 0; i < appendedRecords; i++) {
			result[peak + i] = queryContext.translateEntity(sortedEntities.get(skippedRecords + i));
		}

		// and pass the entities without a listed value on
		return sortingContext.createResultContext(
			notFoundRecords.isEmpty() ?
				EmptyBitmap.INSTANCE : new BaseBitmap(notFoundRecords),
			appendedRecords,
			skippedRecords
		);
	}

	/**
	 * Traversal metadata and lazy lookups. The grouping holds partition handles only; no attribute data from other
	 * blocks is read. Partitions of a target retain the natural ordering's representative key then scope precedence.
	 *
	 * @param referenceSchema    the reference whose rows represent owners
	 * @param attributeSchema    the sorted reference attribute
	 * @param locale             the locale for a localized attribute, otherwise null
	 * @param attributeExtractor reads the sorted attribute from a reference row
	 * @param normalizer         canonicalizes listed and prefetched values as the partition's sort index does
	 * @param partitionsByTarget partition handles grouped by target, preserving each target's partition order
	 */
	private record TraverseOrdering(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale,
		@Nonnull Function<ReferenceContract, Serializable> attributeExtractor,
		@Nonnull UnaryOperator<Serializable> normalizer,
		@Nonnull Map<Integer, List<ReferencePartition>> partitionsByTarget
	) {
		/**
		 * Resolves the block's valued owners once per partition. Earlier valued rows exclude later ones regardless
		 * of whether their values are listed; the listed-value lookups are then intersected with those masks.
		 *
		 * @param blockKey the currently visited target
		 * @return the block-local exact lookup, merging equal-value owners in ascending primary key order
		 */
		@Nonnull
		private Function<Serializable, Bitmap> createLookup(@Nonnull RepresentativeReferenceKey blockKey) {
			final List<PartitionLookup> lookups = new ArrayList<>();
			final PersistentRoaringBitmap represented = new PersistentRoaringBitmap();
			final AttributeIndexKey key = AttributeIndex.createAttributeKey(
				this.referenceSchema, this.attributeSchema, this.locale
			);
			final int targetPrimaryKey = blockKey.referenceKey().primaryKey();
			for (final ReferencePartition partition : this.partitionsByTarget.get(targetPrimaryKey)) {
				final PartitionLookup lookup = switch (partition) {
					case ReducedEntityIndex index -> {
						final SortIndex sortIndex = index.getSortIndex(
							this.referenceSchema, this.attributeSchema, this.locale
						);
						yield sortIndex == null ? null : new PartitionLookup(
							sortIndex.getAscendingOrderRecordsSupplier().getAllRecords(), sortIndex::getRecordsEqualTo
						);
					}
					case VirtualReferencePartition virtual ->
						virtual.typeIndex().getVirtualReferencePartitionIndex()
							.hasSortValue(key, virtual.primaryKey()) ?
							new PartitionLookup(
								new BaseBitmap(virtual.ownerPrimaryKey()),
								value -> VirtualPartitionSortedRecords.getRecordsEqualTo(virtual, key, value)
							) : null;
				};
				if (lookup != null) {
					final PersistentRoaringBitmap valued = RoaringBitmapBackedBitmap.getRoaringBitmap(lookup.owners());
					final PersistentRoaringBitmap firstValued = PersistentRoaringBitmap.andNot(valued, represented);
					lookups.add(new PartitionLookup(new BaseBitmap(firstValued), lookup.lookup()));
					represented.or(valued);
				}
			}
			return value -> {
				final PersistentRoaringBitmap matching = new PersistentRoaringBitmap();
				for (final PartitionLookup lookup : lookups) {
					matching.or(
						PersistentRoaringBitmap.and(
							RoaringBitmapBackedBitmap.getRoaringBitmap(lookup.owners()),
							RoaringBitmapBackedBitmap.getRoaringBitmap(lookup.lookup().apply(value))
						)
					);
				}
				return matching.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(matching);
			};
		}

		/**
		 * Reads the same first valued row that a natural traverse comparator selects, before testing list membership.
		 *
		 * @param entity   the owner
		 * @param blockKey the currently visited target
		 * @return the representing row's value, or null when no row carries the attribute
		 */
		@Nullable
		private Serializable getValue(@Nonnull EntityContract entity, @Nonnull RepresentativeReferenceKey blockKey) {
			return getFirstValuedReference(
				entity, this.referenceSchema, blockKey.referenceKey(),
				reference -> this.attributeExtractor.apply(reference) != null
			).map(this.attributeExtractor).map(this.normalizer).orElse(null);
		}

		/**
		 * Admits the scopes of all partitions of this block, including both live and archived owners.
		 *
		 * @param blockKey the currently visited target
		 * @return the scopes supplying rows to this block
		 */
		@Nonnull
		private Set<Scope> getScopes(@Nonnull RepresentativeReferenceKey blockKey) {
			final Set<Scope> scopes = EnumSet.noneOf(Scope.class);
			final int targetPrimaryKey = blockKey.referenceKey().primaryKey();
			for (final ReferencePartition partition : this.partitionsByTarget.get(targetPrimaryKey)) {
				scopes.add(partition.getIndexKey().scope());
			}
			return scopes;
		}
	}

	/**
	 * Owners represented by one partition and its equality lookup; owners are narrowed to first valued rows before
	 * the lookup is used.
	 *
	 * @param owners owners represented by the partition
	 * @param lookup the partition's exact value lookup
	 */
	private record PartitionLookup(@Nonnull Bitmap owners, @Nonnull Function<Serializable, Bitmap> lookup) {
	}

	/**
	 * The {@link EntityComparator} implementation that orders entities by the position of their attribute value in
	 * the exact order. Every entity is classified by {@link #canSort(EntityContract)} before any comparison: an entity
	 * without the attribute, or with a value the exact order does not list, is not sortable and is never compared -
	 * the sorter passes it on to the next sorter.
	 */
	@SuppressWarnings({"ObjectInstantiationInEqualsHashCode", "ComparatorNotSerializable"})
	@RequiredArgsConstructor
	private static class AttributePositionComparator implements EntityComparator {
		/**
		 * Marker of an entity whose position has not been resolved yet - no resolved position can be negative.
		 */
		private static final int NOT_RESOLVED = Integer.MIN_VALUE;
		/**
		 * Reads the entity attribute or the representing row's reference attribute.
		 */
		private final Function<EntityContract, Serializable> attributeExtractor;
		/**
		 * Listed values in their requested order.
		 */
		private final Serializable[] attributeValues;
		/**
		 * Scopes of the block's partitions, or the single scope of an entity-level index lookup.
		 */
		private final Set<Scope> scopes;
		/**
		 * Expected number of owners, used to size the position caches.
		 */
		private int estimatedCount = 100;
		/**
		 * Memoized positions of attribute values in the exact list.
		 */
		private ObjectIntMap<Serializable> cache;
		/**
		 * Resolved positions keyed by entity primary key, so that every entity is resolved only once, although it
		 * takes part in many comparisons.
		 */
		private IntIntMap positionsByPrimaryKey;

		/**
		 * Starts classification of one selection with an empty owner-position cache.
		 *
		 * @param entityCount expected number of owners
		 */
		@Override
		public void prepareFor(int entityCount) {
			this.estimatedCount = entityCount;
			this.positionsByPrimaryKey = null;
		}

		/**
		 * Every entity is classified before it is compared, so there is never anything to report.
		 *
		 * @return an empty iterable
		 */
		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return Collections.emptyList();
		}

		/**
		 * Classifies an entity when its representing partition's scope is admitted and its attribute value is
		 * listed in the exact order. The position is memoized for the comparisons that follow.
		 *
		 * @param entity the entity to classify
		 * @return `true` when the entity is to be sorted by this comparator
		 */
		@Override
		public boolean canSort(@Nonnull EntityContract entity) {
			return this.scopes.contains(entity.getScope()) && getPosition(entity) != Integer.MAX_VALUE;
		}

		/**
		 * Compares listed positions. Stable sorting retains the ascending input primary key order for ties.
		 *
		 * @param o1 the first admitted entity
		 * @param o2 the second admitted entity
		 * @return the position comparison
		 */
		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			return Integer.compare(getPosition(o1), getPosition(o2));
		}

		/**
		 * Returns the position of the entity's attribute value in {@link #attributeValues}. Entities that miss
		 * the attribute or whose value is not part of the exact order get {@link Integer#MAX_VALUE} - they are
		 * classified as not sortable and never compared.
		 *
		 * @param entity the entity to locate
		 * @return the position of the entity's attribute value or {@link Integer#MAX_VALUE} for non-sorted entities
		 */
		private int getPosition(@Nonnull EntityContract entity) {
			if (this.positionsByPrimaryKey == null) {
				this.positionsByPrimaryKey = new IntIntHashMap(this.estimatedCount);
			}
			final int primaryKey = entity.getPrimaryKeyOrThrowException();
			final int resolvedPosition = this.positionsByPrimaryKey.getOrDefault(primaryKey, NOT_RESOLVED);
			if (resolvedPosition != NOT_RESOLVED) {
				return resolvedPosition;
			}

			final Serializable attribute = this.attributeExtractor.apply(entity);
			final int position;
			if (attribute == null) {
				position = -1;
			} else {
				if (this.cache == null) {
					// let's create the cache with estimated size multiply 5 expected steps for binary search
					this.cache = new ObjectIntHashMap<>(this.estimatedCount * 5);
				}
				position = computeIfAbsent(this.cache, attribute, it -> ArrayUtils.indexOf(it, this.attributeValues));
			}
			if (position < 0) {
				this.positionsByPrimaryKey.put(primaryKey, Integer.MAX_VALUE);
				return Integer.MAX_VALUE;
			} else {
				this.positionsByPrimaryKey.put(primaryKey, position);
				return position;
			}
		}

		/**
		 * This method is used to cache the results of the `indexOf` method. It is used to speed up the
		 * sorting process.
		 *
		 * @param cache        cache to use
		 * @param attribute    attribute of the entity to find
		 * @param indexLocator function to compute the index of the entity
		 * @return index of the entity
		 */
		private static int computeIfAbsent(
			@Nonnull ObjectIntMap<Serializable> cache,
			@Nonnull Serializable attribute,
			@Nonnull ToIntFunction<Serializable> indexLocator
		) {
			final int result = cache.get(attribute);
			// when the value was not found 0 is returned
			if (result == 0) {
				final int computedIndex = indexLocator.applyAsInt(attribute);
				// remap position zero to distinguish a cached position from the map's missing-entry marker
				cache.put(attribute, computedIndex == 0 ? Integer.MIN_VALUE : computedIndex);
				return computedIndex;
			} else if (result == Integer.MIN_VALUE) {
				// when the "rare" value was found - we know it represents index 0
				return 0;
			} else {
				// otherwise cached value was found
				return result;
			}
		}

	}

}
