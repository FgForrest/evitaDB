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

package io.evitadb.core.query.sort.primaryKey.translator;

import com.carrotsearch.hppc.IntIntHashMap;
import io.evitadb.api.query.order.EntityPrimaryKeyNatural;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.comparator.IntComparator;
import io.evitadb.comparator.IntComparator.IntAscendingComparator;
import io.evitadb.comparator.IntComparator.IntDescendingComparator;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.MergeModeDefinition;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.attribute.comparator.PickFirstReferenceOrder;
import io.evitadb.core.query.sort.generic.PrefetchedRecordsSorter;
import io.evitadb.core.query.sort.primaryKey.comparator.ReferencePrimaryKeyComparator;
import io.evitadb.core.query.sort.primaryKey.sorter.ReversedPrimaryKeySorter;
import io.evitadb.core.query.sort.reference.sorter.PickFirstReducedIndexResolver;
import io.evitadb.core.query.sort.reference.sorter.PickFirstReferenceSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.dataType.iterator.EmptyIterator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.core.query.sort.EntityReferenceSensitiveComparator.getReferenceByRepresentativeReferenceKey;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link EntityPrimaryKeyNatural} to {@link Sorter}.
 * It allows to sort entities based on their primary key or primary key of referenced entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EntityPrimaryKeyNaturalTranslator
	implements OrderingConstraintTranslator<EntityPrimaryKeyNatural>, ReferenceOrderingConstraintTranslator<EntityPrimaryKeyNatural> {
	/**
	 * Marks an entity without any reference in {@link PickFirstReferencePrimaryKeyEntityComparator}; referenced
	 * primary keys are positive.
	 */
	private static final int MISSING = Integer.MIN_VALUE;

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull EntityPrimaryKeyNatural entityPrimaryKeyNatural, @Nonnull OrderByVisitor orderByVisitor) {
		final OrderDirection orderDirection = entityPrimaryKeyNatural.getOrderDirection();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final ReferenceSchema referenceSchema = processingScope.referenceSchema();
		final PickFirstReducedIndexResolver pickFirstIndexResolver = processingScope.pickFirstIndexResolver();
		if (referenceSchema == null) {
			return orderDirection == OrderDirection.DESC ? Stream.of(ReversedPrimaryKeySorter.INSTANCE) : Stream.empty();
		} else if (pickFirstIndexResolver != null) {
			// if prefetch happens, the comparator reads the references - and, for a reference allowing duplicates,
			// their representative attributes, which order the references sharing one referenced entity
			final List<String> representativeAttributes = referenceSchema.getCardinality().allowsDuplicates() ?
				referenceSchema.getRepresentativeAttributeDefinition().getAttributeNames() : List.of();
			orderByVisitor.addRequirementToPrefetch(
				representativeAttributes.isEmpty() ?
					referenceContent(referenceSchema.getName()) :
					referenceContentWithAttributes(
						referenceSchema.getName(), representativeAttributes.toArray(String[]::new)
					)
			);
			return Stream.of(
				new PrefetchedRecordsSorter(
					new PickFirstReferencePrimaryKeyEntityComparator(referenceSchema, orderDirection, pickFirstIndexResolver)
				),
				new PickFirstReferenceSorter(
					pickFirstIndexResolver,
					index -> createReferencedPrimaryKeyProvider(index, orderDirection),
					orderDirection == OrderDirection.DESC ? Comparator.naturalOrder().reversed() : Comparator.naturalOrder(),
					orderDirection
				)
			);
		} else {
			final EntityIndex[] entityIndices = processingScope.entityIndex();
			return Stream.of(
				new PreSortedRecordsSorter(
					ofNullable(processingScope.mergeModeDefinition())
						.map(MergeModeDefinition::mergeMode)
						.orElse(MergeMode.APPEND_FIRST),
					orderDirection == OrderDirection.DESC ? Comparator.naturalOrder().reversed() : Comparator.naturalOrder(),
					orderDirection,
					() -> Arrays.stream(entityIndices)
						.map(
							entityIndex -> {
								final Serializable discriminator = entityIndex.getIndexKey().discriminator();
								final Bitmap bitmap = entityIndex.getAllPrimaryKeys();
								final int[] pkArray = bitmap.getArray();
								if (pkArray.length == 0) {
									return null;
								} else if (bitmap instanceof TransactionalBitmap txBitmap) {
									if (discriminator instanceof RepresentativeReferenceKey rrk) {
										return new ReferenceSortedRecordsProvider(
											txBitmap.getId(), pkArray, createPositionArray(bitmap.size()), bitmap,
											position -> rrk.primaryKey(),
											rrk
										);
									} else {
										throw new GenericEvitaInternalError(
											"Entity index " + entityIndex + " is expected to be ReducedEntityIndex with ReferenceKey as discriminator!"
										);
									}
								} else {
									throw new GenericEvitaInternalError(
										"Bitmap " + bitmap + " is not transactional and cannot be used for sorting!"
									);
								}
							}
						)
						.filter(Objects::nonNull)
						.toArray(SortedRecordsSupplier[]::new)
				),
				new PrefetchedRecordsSorter(
					new ReferencePrimaryKeyEntityComparator(
						referenceSchema,
						orderDirection == OrderDirection.DESC ?
							IntDescendingComparator.INSTANCE : IntAscendingComparator.INSTANCE
					)
				)
			);
		}
	}

	@Override
	public void createComparator(
		@Nonnull EntityPrimaryKeyNatural entityPrimaryKeyNatural,
		@Nonnull ReferenceOrderByVisitor orderByVisitor
	) {
		final OrderDirection orderDirection = entityPrimaryKeyNatural.getOrderDirection();

		final ReferenceComparator comparator = new ReferencePrimaryKeyComparator(
			orderDirection == OrderDirection.DESC ?
				IntDescendingComparator.INSTANCE : IntAscendingComparator.INSTANCE
		);

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addComparator(comparator);
	}

	/**
	 * Creates the provider of one reduced index for a `pickFirst` ordering by the referenced primary key: every owner
	 * of the index carries the same value, the primary key of the index's referenced entity, and owners follow in the
	 * order of their own primary keys in the direction of the ordering - the order the merge keeps for equal values.
	 * The owners are read from the index's bitmap whatever its implementation.
	 *
	 * @param index          the reduced index
	 * @param orderDirection the direction of the ordering
	 * @return the provider or `null` when the index holds no owner
	 */
	@Nullable
	private static SortedRecordsSupplier createReferencedPrimaryKeyProvider(
		@Nonnull ReducedEntityIndex index,
		@Nonnull OrderDirection orderDirection
	) {
		final Bitmap owners = index.getAllPrimaryKeys();
		final int size = owners.size();
		if (size == 0) {
			return null;
		}
		final int referencedPrimaryKey = index.getReferenceKey().primaryKey();
		final int[] ownersAscending = owners.getArray();
		final int[] positions = new int[size];
		final int[] sortedOwners;
		if (orderDirection == OrderDirection.DESC) {
			sortedOwners = ArrayUtils.reverse(ownersAscending);
			for (int i = 0; i < size; i++) {
				positions[i] = size - 1 - i;
			}
		} else {
			sortedOwners = ownersAscending;
			for (int i = 0; i < size; i++) {
				positions[i] = i;
			}
		}
		return new SortedRecordsSupplier(
			index.getPrimaryKey(), sortedOwners, positions, owners, position -> referencedPrimaryKey
		);
	}

	/**
	 * Creates an array of positions from 0 to size - 1.
	 *
	 * @param size the size of the array
	 * @return an array of positions from 0 to size - 1
	 */
	@Nonnull
	private static int[] createPositionArray(int size) {
		final int[] result = new int[size];
		for (int i = 0; i < size; i++) {
			result[i] = i;
		}
		return result;
	}

	/**
	 * A comparator that compares entities based on the primary key of the referenced entity.
	 */
	@SuppressWarnings("ComparatorNotSerializable")
	@RequiredArgsConstructor
	private static class ReferencePrimaryKeyEntityComparator implements EntityComparator, EntityReferenceSensitiveComparator {
		/**
		 * The schema of the reference that is being traversed.
		 */
		private final ReferenceSchema referenceSchema;
		/**
		 * The id of the referenced entity that is being traversed.
		 */
		@Nullable private RepresentativeReferenceKey referenceKey;
		/**
		 * The comparator used to compare the primary keys of the referenced entities.
		 */
		@Nonnull private final IntComparator comparator;
		/**
		 * The array that collects non-sorted entities.
		 */
		private CompositeObjectArray<EntityContract> nonSortedEntities;

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return this.nonSortedEntities == null ?
				EmptyIterator.iterableInstance(EntityContract.class) :
				this.nonSortedEntities;
		}

		@Override
		public void withReferencedEntityId(@Nonnull RepresentativeReferenceKey referenceKey, @Nonnull Runnable lambda) {
			try {
				Assert.isPremiseValid(this.referenceKey == null, "Cannot set referenced entity id twice!");
				Assert.isPremiseValid(
					this.referenceSchema.getName().equals(referenceKey.referenceName()),
					"Referenced entity id must be for the same reference!"
				);
				this.referenceKey = referenceKey;
				lambda.run();
			} finally {
				this.referenceKey = null;
			}
		}

		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			Assert.isPremiseValid(this.referenceKey != null, "Referenced entity id must be set!");

			final ReferenceContract o1Reference = getReferenceByRepresentativeReferenceKey(o1, this.referenceSchema, this.referenceKey).orElse(null);
			final ReferenceContract o2Reference = getReferenceByRepresentativeReferenceKey(o2, this.referenceSchema, this.referenceKey).orElse(null);
			if (o1Reference == null && o2Reference == null) {
				this.nonSortedEntities = getOrCreatedNonSortedEntitiesCollector();
				this.nonSortedEntities.add(o1);
				this.nonSortedEntities.add(o2);
				return 0;
			} else if (o1Reference == null) {
				this.nonSortedEntities = getOrCreatedNonSortedEntitiesCollector();
				this.nonSortedEntities.add(o1);
				return 1;
			} else if (o2Reference == null) {
				this.nonSortedEntities = getOrCreatedNonSortedEntitiesCollector();
				this.nonSortedEntities.add(o2);
				return -1;
			} else {
				return this.comparator.compare(
					o1Reference.getReferencedPrimaryKey(),
					o2Reference.getReferencedPrimaryKey()
				);
			}
		}

		@Nonnull
		private CompositeObjectArray<EntityContract> getOrCreatedNonSortedEntitiesCollector() {
			return ofNullable(this.nonSortedEntities)
				.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
		}
	}

	/**
	 * Prefetch route of a `pickFirst` ordering by the referenced primary key: an entity sorts on the primary key of its
	 * first reference in target order - exactly the reference whose reduced index claims it on the index route - and
	 * entities with equal values follow in the order of their own primary keys in the direction of the ordering.
	 * Entities with no reference are left unsorted.
	 */
	@SuppressWarnings("ComparatorNotSerializable")
	private static class PickFirstReferencePrimaryKeyEntityComparator implements EntityComparator {
		/**
		 * The reference being ordered by.
		 */
		@Nonnull private final ReferenceSchema referenceSchema;
		/**
		 * Resolver providing the target order the index route walks.
		 */
		@Nonnull private final PickFirstReducedIndexResolver indexResolver;
		/**
		 * Compares the picked referenced primary keys in the direction of the ordering.
		 */
		@Nonnull private final IntComparator valueComparator;
		/**
		 * Compares entities with equal values by their primary key in the direction of the ordering.
		 */
		@Nonnull private final IntComparator primaryKeyComparator;
		/**
		 * Picks the first reference in target order.
		 */
		@Nonnull private final Comparator<ReferenceContract> referenceOrder;
		/**
		 * Memoized picked referenced primary key of each compared entity, {@link #MISSING} for an entity with none.
		 */
		@Nonnull private final IntIntHashMap pickedValues = new IntIntHashMap(64);
		/**
		 * Rank of the targets of the entities being sorted, set by {@link #prepareForSelection(Bitmap)}.
		 */
		@Nullable private IntUnaryOperator targetRank;
		/**
		 * The array that collects non-sorted entities.
		 */
		@Nullable private CompositeObjectArray<EntityContract> nonSortedEntities;

		PickFirstReferencePrimaryKeyEntityComparator(
			@Nonnull ReferenceSchema referenceSchema,
			@Nonnull OrderDirection orderDirection,
			@Nonnull PickFirstReducedIndexResolver indexResolver
		) {
			this.referenceSchema = referenceSchema;
			this.indexResolver = indexResolver;
			this.valueComparator = orderDirection == OrderDirection.DESC ?
				IntDescendingComparator.INSTANCE : IntAscendingComparator.INSTANCE;
			this.primaryKeyComparator = this.valueComparator;
			this.referenceOrder = PickFirstReferenceOrder.create(
				referenceSchema,
				referencedPrimaryKey -> {
					Assert.isPremiseValid(
						this.targetRank != null,
						"The comparator must be prepared for the selection before it compares entities!"
					);
					return this.targetRank.applyAsInt(referencedPrimaryKey);
				}
			);
		}

		@Override
		public void prepareFor(int entityCount) {
			this.pickedValues.clear();
			this.nonSortedEntities = null;
		}

		@Override
		public void prepareForSelection(@Nonnull Bitmap entityPrimaryKeys) {
			this.targetRank = this.indexResolver.getTargetRank(entityPrimaryKeys);
		}

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return this.nonSortedEntities == null ?
				EmptyIterator.iterableInstance(EntityContract.class) :
				this.nonSortedEntities;
		}

		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			final int value1 = pickValue(o1);
			final int value2 = pickValue(o2);
			if (value1 != MISSING && value2 != MISSING) {
				final int result = this.valueComparator.compare(value1, value2);
				return result == 0 ?
					this.primaryKeyComparator.compare(o1.getPrimaryKeyOrThrowException(), o2.getPrimaryKeyOrThrowException()) :
					result;
			} else if (value1 != MISSING) {
				return -1;
			} else if (value2 != MISSING) {
				return 1;
			} else {
				return 0;
			}
		}

		/**
		 * Returns the referenced primary key of the first reference of the entity in target order, memoized.
		 *
		 * @param entity the compared entity
		 * @return the referenced primary key or {@link #MISSING} when the entity has no reference
		 */
		private int pickValue(@Nonnull EntityContract entity) {
			final int entityPrimaryKey = entity.getPrimaryKeyOrThrowException();
			final int index = this.pickedValues.indexOf(entityPrimaryKey);
			if (this.pickedValues.indexExists(index)) {
				return this.pickedValues.indexGet(index);
			}
			final int value = entity.getReferences(this.referenceSchema.getName())
				.stream()
				.min(this.referenceOrder)
				.map(ReferenceContract::getReferencedPrimaryKey)
				.orElse(MISSING);
			this.pickedValues.indexInsert(index, entityPrimaryKey, value);
			if (value == MISSING) {
				if (this.nonSortedEntities == null) {
					this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
				}
				this.nonSortedEntities.add(entity);
			}
			return value;
		}
	}

}
