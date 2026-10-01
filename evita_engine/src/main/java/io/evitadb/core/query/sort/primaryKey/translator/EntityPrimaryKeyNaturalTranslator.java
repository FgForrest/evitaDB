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
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.MergeModeDefinition;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.ScopedSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.comparator.PickFirstReferenceTargetRanking;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
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
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
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
			final Bitmap recordsOfNarrowedScopes = orderByVisitor.getRecordsOfNarrowedScopes();
			if (recordsOfNarrowedScopes != null) {
				// an order in an `inScope` container sorts only the entities of its scope, at its declared position
				return Stream.of(
					new ScopedSorter(
						recordsOfNarrowedScopes,
						orderDirection == OrderDirection.DESC ? ReversedPrimaryKeySorter.INSTANCE : NoSorter.INSTANCE
					)
				);
			} else if (orderDirection == OrderDirection.DESC) {
				return Stream.of(ReversedPrimaryKeySorter.INSTANCE);
			} else if (orderByVisitor.isWithinSegment()) {
				// a segment chain has no default sorter, so the ascending order is registered at its declared position
				return Stream.of(NoSorter.INSTANCE);
			} else {
				// the default sorter appended to every chain applies the ascending order
				return Stream.empty();
			}
		} else if (pickFirstIndexResolver != null) {
			// if prefetch happens, the comparator reads the references - and, for a reference allowing duplicates,
			// their representative attributes, which order the references sharing one referenced entity
			registerReferencesWithRepresentativeAttributesToPrefetch(referenceSchema, orderByVisitor);
			return Stream.of(
				new PrefetchedRecordsSorter(
					new PickFirstReferencePrimaryKeyEntityComparator(referenceSchema, orderDirection, pickFirstIndexResolver)
				),
				new PickFirstReferenceSorter(
					pickFirstIndexResolver,
					index -> () -> createReferencedPrimaryKeyProvider(index, orderDirection),
					orderDirection == OrderDirection.DESC ? Comparator.naturalOrder().reversed() : Comparator.naturalOrder(),
					orderDirection
				)
			);
		} else {
			// if prefetch happens, the traversal comparator classifies every owner of a block by whether it has a row
			// to the block's referenced entity - it reads the rows, never their attributes
			orderByVisitor.addRequirementToPrefetch(referenceContent(referenceSchema.getName()));
			final EntityIndex[] entityIndices = processingScope.entityIndex();
			// every owner of a block carries the same value, so both routes order a block by the owner primary key in
			// the direction of the ordering: the comparator breaks the tie that way and each provider lists its
			// owners that way
			return Stream.of(
				new PrefetchedRecordsSorter(
					new ReferencePrimaryKeyEntityComparator(
						referenceSchema,
						orderDirection == OrderDirection.DESC ?
							IntDescendingComparator.INSTANCE : IntAscendingComparator.INSTANCE
					)
				),
				new PreSortedRecordsSorter(
					ofNullable(processingScope.mergeModeDefinition())
						.map(MergeModeDefinition::mergeMode)
						.orElse(MergeMode.APPEND_FIRST),
					orderDirection == OrderDirection.DESC ? Comparator.naturalOrder().reversed() : Comparator.naturalOrder(),
					orderDirection,
					() -> Arrays.stream(entityIndices)
						.map(
							entityIndex -> {
								if (entityIndex instanceof ReducedEntityIndex reducedEntityIndex) {
									return createReferencedPrimaryKeyProvider(reducedEntityIndex, orderDirection);
								} else {
									throw new GenericEvitaInternalError(
										"Entity index " + entityIndex + " is expected to be ReducedEntityIndex with ReferenceKey as discriminator!"
									);
								}
							}
						)
						.filter(Objects::nonNull)
						.toArray(SortedRecordsSupplier[]::new)
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
	 * Registers the content the prefetch-route comparator of a `pickFirst` ordering by the referenced primary key
	 * reads: the references of the ordered reference and, for a reference allowing duplicates, their representative
	 * attributes, which order the references sharing one referenced entity.
	 *
	 * @param referenceSchema the ordered reference
	 * @param orderByVisitor  the visitor collecting the requirements of the prefetch
	 */
	private static void registerReferencesWithRepresentativeAttributesToPrefetch(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull OrderByVisitor orderByVisitor
	) {
		final List<String> representativeAttributes = referenceSchema.getCardinality().allowsDuplicates() ?
			referenceSchema.getRepresentativeAttributeDefinition().getAttributeNames() : List.of();
		orderByVisitor.addRequirementToPrefetch(
			representativeAttributes.isEmpty() ?
				referenceContent(referenceSchema.getName()) :
				referenceContentWithAttributes(
					referenceSchema.getName(), representativeAttributes.toArray(String[]::new)
				)
		);
	}

	/**
	 * Creates the provider of one reduced index for an ordering by the referenced primary key - a `pickFirst` one or a
	 * traversal: every owner of the index carries the same value, the primary key of the index's referenced entity,
	 * and owners follow in the order of their own primary keys in the direction of the ordering - the order the merge
	 * keeps for equal values. A descending provider mirrors the positions of the ascending owner array instead of
	 * sorting anything. The provider carries the representative reference key of the index, which the traversal uses
	 * to find the providers of a block. The owners are read from the index's bitmap whatever its implementation.
	 *
	 * @param index          the reduced index
	 * @param orderDirection the direction of the ordering
	 * @return the provider or `null` when the index holds no owner
	 */
	@Nullable
	private static ReferenceSortedRecordsProvider createReferencedPrimaryKeyProvider(
		@Nonnull ReducedEntityIndex index,
		@Nonnull OrderDirection orderDirection
	) {
		final Bitmap owners = index.getAllPrimaryKeys();
		final int size = owners.size();
		if (size == 0) {
			return null;
		}
		// boxed once - the merge of a block's providers asks for the value of every record it compares
		final Integer referencedPrimaryKey = index.getReferenceKey().primaryKey();
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
		return new ReferenceSortedRecordsProvider(
			index.getPrimaryKey(), sortedOwners, positions, owners, position -> referencedPrimaryKey,
			index.getRepresentativeReferenceKey()
		);
	}

	/**
	 * A comparator that compares entities based on the primary key of the referenced entity, one traversal block at
	 * a time: the block key, naming the block's referenced entity, is bound by
	 * {@link #withReferencedEntityId(RepresentativeReferenceKey, Runnable)} and an entity without a row to it is
	 * classified as not sortable ({@link #canSort(EntityContract)}), so it is passed on to the next block rather than
	 * claimed by this one. Every owner of a block references the same entity,
	 * so the owners of a block follow their own primary keys in the direction of the ordering, as on the index route.
	 * Package-private so that its classification can be unit-tested over real prefetched entities.
	 */
	@SuppressWarnings("ComparatorNotSerializable")
	@RequiredArgsConstructor
	static class ReferencePrimaryKeyEntityComparator implements EntityComparator, EntityReferenceSensitiveComparator {
		/**
		 * The schema of the reference that is being traversed.
		 */
		private final ReferenceSchema referenceSchema;
		/**
		 * The id of the referenced entity that is being traversed.
		 */
		@Nullable private RepresentativeReferenceKey referenceKey;
		/**
		 * Compares the owner primary keys in the direction of the ordering - the tie-break of owners sharing the
		 * value of their block.
		 */
		@Nonnull private final IntComparator comparator;

		/**
		 * Returns no entity: every entity without a row of the bound block is classified as not sortable by
		 * {@link #canSort(EntityContract)} and never compared, so there is nothing left to report.
		 *
		 * @return an empty iterable
		 */
		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return EmptyIterator.iterableInstance(EntityContract.class);
		}

		/**
		 * Binds the comparator to the key of the sorted block for the duration of the lambda, which classifies and
		 * sorts the entities of the block; the key is released whatever the outcome.
		 *
		 * @param referenceKey the key of the sorted block
		 * @param lambda       the classification and sorting of the block
		 */
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

		/**
		 * Classifies the entity for the bound block: it is sortable when it has any row to the block's referenced
		 * entity - every row carries the value, so whichever row represents the entity, the value is the same and
		 * only the existence of a row is checked; no row is picked and no representative value is read.
		 *
		 * @param entity the entity to classify
		 * @return `true` when the entity has a row to the referenced entity of the bound block
		 */
		@Override
		public boolean canSort(@Nonnull EntityContract entity) {
			Assert.isPremiseValid(this.referenceKey != null, "Referenced entity id must be set!");
			return !entity.getReferences(this.referenceKey.referenceKey()).isEmpty();
		}

		/**
		 * Compares two entities classified as sortable by {@link #canSort(EntityContract)} within the bound block.
		 * Both have a row to the block's referenced entity, so both carry the same value - its primary key -
		 * and the tie is broken by their own primary keys in the direction of the ordering. Neither row is
		 * looked up again: the classification already established it, and the value it would yield is fixed by the
		 * block key.
		 *
		 * @param o1 the first entity
		 * @param o2 the second entity
		 * @return the comparison result
		 */
		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			Assert.isPremiseValid(this.referenceKey != null, "Referenced entity id must be set!");
			return this.comparator.compare(o1.getPrimaryKeyOrThrowException(), o2.getPrimaryKeyOrThrowException());
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
		 * Resolver and derived reference order providing the target order the index route walks.
		 */
		@Nonnull private final PickFirstReferenceTargetRanking targetRanking;
		/**
		 * Compares the picked referenced primary keys in the direction of the ordering.
		 */
		@Nonnull private final IntComparator valueComparator;
		/**
		 * Compares entities with equal values by their primary key in the direction of the ordering.
		 */
		@Nonnull private final IntComparator primaryKeyComparator;
		/**
		 * Memoized picked referenced primary key of each compared entity, {@link #MISSING} for an entity with none.
		 */
		@Nonnull private final IntIntHashMap pickedValues = new IntIntHashMap(64);
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
			this.targetRanking = new PickFirstReferenceTargetRanking(referenceSchema, indexResolver);
			this.valueComparator = orderDirection == OrderDirection.DESC ?
				IntDescendingComparator.INSTANCE : IntAscendingComparator.INSTANCE;
			this.primaryKeyComparator = this.valueComparator;
		}

		@Override
		public void prepareFor(int entityCount) {
			this.pickedValues.clear();
			this.nonSortedEntities = null;
		}

		@Override
		public void prepareForSelection(@Nonnull Bitmap entityPrimaryKeys) {
			this.targetRanking.prepareForSelection(entityPrimaryKeys);
		}

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return this.nonSortedEntities == null ?
				EmptyIterator.iterableInstance(EntityContract.class) :
				this.nonSortedEntities;
		}

		/**
		 * Classifies the entity: it is sortable when it is admitted by the ordering's scopes and has a reference to
		 * pick. The picked value is memoized for the comparisons that follow, and an entity classified as not
		 * sortable is not reported through {@link #getNonSortedEntities()} - the caller passes it on by itself.
		 *
		 * @param entity the entity to classify
		 * @return `true` when the entity has a referenced primary key to be sorted by
		 */
		@Override
		public boolean canSort(@Nonnull EntityContract entity) {
			return memoizeValue(entity, false) != MISSING;
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
			return memoizeValue(entity, true);
		}

		/**
		 * Returns the referenced primary key of the first reference of the entity in target order, memoized. An
		 * entity without one is reported through {@link #getNonSortedEntities()} when `reportMissing` is set and the
		 * value is computed by this call - i.e. only from the comparison path, never from the classification.
		 *
		 * @param entity        the entity whose value is requested
		 * @param reportMissing whether a freshly computed missing value is reported as a non-sorted entity
		 * @return the referenced primary key or {@link #MISSING} when the entity has no reference
		 */
		private int memoizeValue(@Nonnull EntityContract entity, boolean reportMissing) {
			final int entityPrimaryKey = entity.getPrimaryKeyOrThrowException();
			final int index = this.pickedValues.indexOf(entityPrimaryKey);
			if (this.pickedValues.indexExists(index)) {
				return this.pickedValues.indexGet(index);
			}
			final int value = this.targetRanking.admits(entity) ?
				entity.getReferences(this.referenceSchema.getName())
					.stream()
					.min(this.targetRanking.referenceOrder())
					.map(ReferenceContract::getReferencedPrimaryKey)
					.orElse(MISSING) :
				MISSING;
			this.pickedValues.indexInsert(index, entityPrimaryKey, value);
			if (reportMissing && value == MISSING) {
				if (this.nonSortedEntities == null) {
					this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
				}
				this.nonSortedEntities.add(entity);
			}
			return value;
		}
	}

}
