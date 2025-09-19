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

package io.evitadb.core.query.sort.reference.translator;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.EntityPrimaryKeyNatural;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.api.query.order.ReferenceOrderingSpecification;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.order.TraverseByEntityProperty;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.MergeModeDefinition;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.reference.SequentialSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.RepresentativeReferenceKey;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.pickFirstByEntityProperty;
import static io.evitadb.api.query.QueryConstraints.traverseByEntityProperty;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link ReferenceProperty} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferencePropertyTranslator implements OrderingConstraintTranslator<ReferenceProperty>, SelfTraversingTranslator {

	/**
	 * Method locates all {@link EntityIndex} from the resolved list of {@link TargetIndexes} which were identified
	 * by the {@link IndexSelectionVisitor}. The list is expected to be much smaller than the full list computed
	 * in {@link #selectFullEntityIndexSet(OrderByVisitor, String)}.
	 */
	@Nonnull
	private static List<ReducedEntityIndex> selectReducedEntityIndexSet(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull String referenceName
	) {
		for (TargetIndexes<?> targetIndex : orderByVisitor.getTargetIndexes()) {
			if (ReducedEntityIndex.class.equals(targetIndex.getIndexType())) {
				//noinspection unchecked
				final List<ReducedEntityIndex> reducedIndexes = (List<ReducedEntityIndex>) targetIndex.getIndexes();
				if (
					!reducedIndexes.isEmpty() &&
						reducedIndexes.get(0).getIndexKey().discriminator() instanceof RepresentativeReferenceKey rk &&
						referenceName.equals(rk.referenceName())
				) {
					return reducedIndexes;
				}
			}
		}
		return Collections.emptyList();
	}

	/**
	 * Method locates all {@link EntityIndex} instances that are related to the given reference name. The list is
	 * resolved from {@link ReferencedTypeEntityIndex}.
	 */
	@Nonnull
	private static List<ReducedEntityIndex> selectFullEntityIndexSet(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull String referenceName
	) {
		final Set<Scope> allowedScopes = orderByVisitor.getProcessingScope().getScopes();
		Stream<ReducedEntityIndex> indexes = Stream.empty();
		for (Scope scope : Scope.values()) {
			if (allowedScopes.contains(scope)) {
				final EntityIndexKey entityIndexKey = new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE,
					scope,
					referenceName
				);
				final Optional<ReferencedTypeEntityIndex> referencedEntityTypeIndex = orderByVisitor.getIndexIfExists(
					entityIndexKey, ReferencedTypeEntityIndex.class
				);

				indexes = Stream.concat(
					indexes,
					referencedEntityTypeIndex
						.map(
							it -> it
								.getAllPrimaryKeys()
								.stream()
								.mapToObj(indexPk -> orderByVisitor.getEntityIndexByPrimaryKey(indexPk, ReducedEntityIndex.class))
						)
						.orElse(Stream.empty())
				);
			}
		}
		return indexes.toList();
	}

	/**
	 * Creates a new instance of {@link NestedContextSorter} by configuring the necessary sorting logic based
	 * on the provided ordering constraints, reference schema, and the {@link OrderByVisitor} state.
	 * The created sorter effectively handles nested contexts and applies the specified sort order
	 * for the referenced entity type.
	 *
	 * @param orderByVisitor  The visitor containing the order-by constraints, locale, query context, and processing scope.
	 * @param referenceSchema The schema contract defining the referenced entity type and relationships to be used for sorting.
	 * @param ordering        An array of {@link OrderConstraint} specifying the constraints to determine the sort order.
	 * @return An instance of {@link NestedContextSorter} configured with the given parameters.
	 */
	@Nonnull
	private static NestedContextSorter createNestedContextSorter(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull OrderConstraint[] ordering
	) {
		return OrderByVisitor.createSorter(
			orderBy(ordering),
			orderByVisitor.getLocale(),
			orderByVisitor.getEntityCollectionOrThrowException(
				referenceSchema.getReferencedEntityType(),
				"sort order for reference `" + referenceSchema.getName() + "`"
			),
			() -> "Reference sort `" + referenceSchema.getName() + "`",
			orderByVisitor.getQueryContext(),
			orderByVisitor.getProcessingScope().getScopes()
		);
	}

	/**
	 * This method retrieves and processes a stream of sorted primary keys for reduced entity indices based on
	 * the traversal mode, order constraints, and hierarchical sorting logic. It determines the sorting mechanism
	 * based on the provided order constraints and traverses relevant scopes to aggregate the results.
	 *
	 * @param orderByVisitor           The visitor handling the query context, locale, and processing scope for sorting and traversal.
	 * @param referenceSchema          The schema contract defining the entity references and relationships for sorting.
	 * @param traverseByEntityProperty An array of {@link OrderConstraint}, specifying the constraints for traversing and sorting.
	 * @param traversalMode            The traversal mode dictating how the hierarchy nodes are processed.
	 * @param referenceIndexIds        A formula providing the set of indices to be traversed and sorted.
	 * @return A stream of integers representing the traversed and sorted primary keys of reduced entity indices.
	 */
	@Nonnull
	private static IntStream getTraversedAndSortedReducedIndexPrimaryKeys(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull OrderConstraint[] traverseByEntityProperty,
		@Nonnull TraversalMode traversalMode,
		@Nonnull Formula referenceIndexIds
	) {
		final Set<Scope> allowedScopes = orderByVisitor.getProcessingScope().getScopes();
		IntStream result = IntStream.empty();
		for (Scope scope : Scope.values()) {
			if (allowedScopes.contains(scope)) {
				final Bitmap scopedResult = orderByVisitor.getGlobalEntityIndexIfExists(referenceSchema.getReferencedEntityType(), scope)
					.map(
						it -> it.listHierarchyNodesFromRoot(
							traversalMode,
							createLevelSorter(
								orderByVisitor,
								referenceSchema,
								traverseByEntityProperty,
								referenceIndexIds,
								ids -> {
									final Bitmap nodesWithParents = it.listNodesIncludingParents(ids.compute());
									return nodesWithParents.isEmpty() ?
										EmptyFormula.INSTANCE :
										new ConstantFormula(nodesWithParents);
								}
							)
						)
					)
					.orElse(EmptyBitmap.INSTANCE);
				result = IntStream.concat(result, IntStream.of(scopedResult.getArray()));
			}
		}
		return result;
	}

	@Nonnull
	private static UnaryOperator<int[]> createLevelSorter(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull OrderConstraint[] traverseByEntityProperty,
		@Nonnull Formula referenceIndexIds,
		@Nonnull UnaryOperator<Formula> parentIdsFormula
	) {
		final UnaryOperator<int[]> levelSorter;
		if (traverseByEntityProperty.length == 1 && traverseByEntityProperty[0] instanceof EntityPrimaryKeyNatural epkn) {
			levelSorter = epkn.getOrderDirection() == OrderDirection.ASC ?
				UnaryOperator.identity() : ArrayUtils::reverse;
		} else {
			final NestedContextSorter sorter = createNestedContextSorter(orderByVisitor, referenceSchema, traverseByEntityProperty);
			levelSorter = input -> {
				if (input.length == 0) {
					return ArrayUtils.EMPTY_INT_ARRAY;
				} else {
					return sorter.sortAndSlice(
						FormulaFactory.and(
							parentIdsFormula.apply(referenceIndexIds),
							new ConstantFormula(new BaseBitmap(input))
						).compute()
					);
				}
			};
		}
		return levelSorter;
	}

	/**
	 * Returns a stream of sorted primary keys for reduced index entities based on the specified order constraints.
	 * This method evaluates the provided order constraints and applies the appropriate sorting mechanism to
	 * produce the desired order of primary keys.
	 *
	 * @param orderByVisitor            The visitor used to handle the order-by constraints, query context, and processing scope.
	 * @param referenceSchema           The schema contract defining the referenced entity properties and relationships for sorting.
	 * @param pickFirstByEntityProperty An array of {@link OrderConstraint} that specifies the constraints for sorting by entity properties.
	 * @param referenceIndexIds         A formula that provides the set of reference indices to be sorted.
	 * @return A stream of integers representing the sorted primary keys of the reduced index entities.
	 */
	@Nonnull
	private static IntStream getSortedReducedIndexPrimaryKeys(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull OrderConstraint[] pickFirstByEntityProperty,
		@Nonnull Formula referenceIndexIds
	) {
		if (pickFirstByEntityProperty.length == 1 && pickFirstByEntityProperty[0] instanceof EntityPrimaryKeyNatural epkn) {
			return IntStream.of(
				epkn.getOrderDirection() == OrderDirection.ASC ?
					referenceIndexIds.compute().getArray() : ArrayUtils.reverse(referenceIndexIds.compute().getArray())
			);
		} else {
			final NestedContextSorter sorter = createNestedContextSorter(orderByVisitor, referenceSchema, pickFirstByEntityProperty);
			return IntStream.of(sorter.sortAndSlice(referenceIndexIds));
		}
	}

	/**
	 * Traverses the child constraints within the provided {@link ReferenceProperty}
	 * and applies the specified {@link OrderByVisitor} to handle the ordering constraints.
	 * This method handles specific constraints such as {@link EntityProperty} with
	 * {@link EntityPrimaryKeyNatural}, skipping unsupported or already processed types.
	 *
	 * @param referenceProperty The {@link ReferenceProperty} containing the order constraints to be traversed.
	 * @param orderByVisitor    The {@link OrderByVisitor} responsible for handling and applying the ordering logic
	 *                          during the traversal of constraints.
	 */
	private static void traverseChildConstraints(@Nonnull ReferenceProperty referenceProperty, @Nonnull OrderByVisitor orderByVisitor) {
		for (OrderConstraint innerConstraint : referenceProperty.getOrderConstraints()) {
			// explicit support for `entityProperty(entityPrimaryKeyNatural())` - other variants
			// of `entityProperty` doesn't make sense in this context
			if (innerConstraint instanceof EntityProperty entityProperty) {
				final OrderConstraint[] childrenConstraints = entityProperty.getChildren();
				if (childrenConstraints.length == 1 && childrenConstraints[0] instanceof EntityPrimaryKeyNatural primaryKeyNatural) {
					primaryKeyNatural.accept(orderByVisitor);
					continue;
				}
			} else if (innerConstraint instanceof TraverseByEntityProperty || innerConstraint instanceof PickFirstByEntityProperty) {
				// skip this constraint as it is already handled by this translator
				continue;
			}
			innerConstraint.accept(orderByVisitor);
		}
	}

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull ReferenceProperty referenceProperty, @Nonnull OrderByVisitor orderByVisitor) {
		final String referenceName = referenceProperty.getReferenceName();
		final EntitySchema entitySchema = orderByVisitor.getSchema();
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		for (Scope scope : processingScope.getScopes()) {
			if (!referenceSchema.isIndexedInScope(scope)) {
				throw new ReferenceNotIndexedException(referenceName, entitySchema, scope);
			}
		}
		final boolean referencedEntityHierarchical = referenceSchema.isReferencedEntityTypeManaged() &&
			orderByVisitor.getSchema(referenceSchema.getReferencedEntityType()).isWithHierarchy();

		final Optional<ReferenceOrderingSpecification> orderingSpecificationRef = referenceProperty.getReferenceOrderingSpecification();
		final ReferenceOrderingSpecification orderingSpecification = orderingSpecificationRef
			.orElseGet(
				() -> referencedEntityHierarchical ?
					traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)) :
					pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC))
			);

		final List<ReducedEntityIndex> reducedEntityIndexSet = selectReducedEntityIndexSet(orderByVisitor, referenceName);
		final List<ReducedEntityIndex> referenceIndexes = reducedEntityIndexSet.isEmpty() ?
			selectFullEntityIndexSet(orderByVisitor, referenceName) :
			reducedEntityIndexSet;

		if (!referenceIndexes.isEmpty()) {
			final IntObjectMap<Stream<ReducedEntityIndex>> reducedIndexesMap = new IntObjectHashMap<>(referenceIndexes.size());
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (ReducedEntityIndex referenceIndex : referenceIndexes) {
				final ReferenceKey discriminator = Objects.requireNonNull((RepresentativeReferenceKey) referenceIndex.getIndexKey().discriminator()).referenceKey();
				final int referencedPk = discriminator.primaryKey();
				writer.add(referencedPk);
				final Stream<ReducedEntityIndex> existingValue = reducedIndexesMap.get(referencedPk);
				if (existingValue == null) {
					reducedIndexesMap.put(referencedPk, Stream.of(referenceIndex));
				} else {
					// there should be at most 2 such indexes
					reducedIndexesMap.put(referencedPk, Stream.concat(existingValue, Stream.of(referenceIndex)));
				}
			}
			final Formula referenceIndexIds = new ConstantFormula(new BaseBitmap(writer.get()));

			final MergeMode mergeMode;
			final IntStream sortedReferencePks;
			if (orderingSpecification instanceof TraverseByEntityProperty tbep) {
				mergeMode = MergeMode.APPEND_ALL;
				sortedReferencePks = referencedEntityHierarchical ?
					getTraversedAndSortedReducedIndexPrimaryKeys(
						orderByVisitor, referenceSchema, tbep.getChildren(), tbep.getTraversalMode(), referenceIndexIds
					) :
					getSortedReducedIndexPrimaryKeys(
						orderByVisitor, referenceSchema, tbep.getChildren(), referenceIndexIds
					);
			} else if (orderingSpecification instanceof PickFirstByEntityProperty pfbep) {
				mergeMode = MergeMode.APPEND_FIRST;
				sortedReferencePks = getSortedReducedIndexPrimaryKeys(
					orderByVisitor, referenceSchema, pfbep.getChildren(), referenceIndexIds
				);
			} else {
				throw new GenericEvitaInternalError("Expected initialized ordering specification at least by defaults!");
			}

			// create sorted reduced index array
			final ReducedEntityIndex[] sortedReducedIndexes = sortedReferencePks
				.mapToObj(reducedIndexesMap::get)
				.filter(Objects::nonNull)
				.flatMap(Function.identity())
				.toArray(ReducedEntityIndex[]::new);

			if (mergeMode == MergeMode.APPEND_ALL) {
				int start = 0;
				ReferenceKey referenceKey = null;
				int index = 0;
				final ReducedEntityIndex[][] atomicBlocks = new ReducedEntityIndex[reducedIndexesMap.size()][];
				for (int i = 0; i < sortedReducedIndexes.length; i++) {
					final ReducedEntityIndex sortedReducedIndex = sortedReducedIndexes[i];
					final ReferenceKey srpReferenceKey = sortedReducedIndex.getReferenceKey();
					if (referenceKey != null && !referenceKey.equalsInGeneral(srpReferenceKey)) {
						atomicBlocks[index++] = Arrays.copyOfRange(sortedReducedIndexes, start, i);
						start = i;
					}
					referenceKey = srpReferenceKey;
				}
				atomicBlocks[index] = Arrays.copyOfRange(sortedReducedIndexes, start, sortedReducedIndexes.length);
				Assert.isPremiseValid(index == atomicBlocks.length - 1, "Unexpected number of atomic blocks: " + index);

				final List<Sorter> sorters = orderByVisitor.executeInContext(
					sortedReducedIndexes,
					referenceSchema,
					null,
					processingScope.withReferenceSchemaAccessor(referenceName),
					new MergeModeDefinition(mergeMode, orderingSpecificationRef.isEmpty()),
					() -> orderByVisitor.collectIsolatedSorters(
						() -> traverseChildConstraints(referenceProperty, orderByVisitor)
					)
				);

				return Stream.of(
					new SequentialSorter(atomicBlocks, sorters)
				);
			} else {
				orderByVisitor.executeInContext(
					sortedReducedIndexes,
					referenceSchema,
					null,
					processingScope.withReferenceSchemaAccessor(referenceName),
					new MergeModeDefinition(mergeMode, orderingSpecificationRef.isEmpty()),
					() -> {
						traverseChildConstraints(referenceProperty, orderByVisitor);
						return null;
					}
				);
				return Stream.empty();
			}
		} else {
			return Stream.empty();
		}
	}

}
