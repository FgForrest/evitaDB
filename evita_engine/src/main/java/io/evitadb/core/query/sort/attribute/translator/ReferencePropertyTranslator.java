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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
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
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.Index;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
	private static final Comparator<EntityIndex> DEFAULT_COMPARATOR = (o1, o2) -> {
		final int o1pk = Objects.requireNonNull((ReferenceKey) o1.getIndexKey().discriminator()).primaryKey();
		final int o2pk = Objects.requireNonNull((ReferenceKey) o2.getIndexKey().discriminator()).primaryKey();
		return Integer.compare(o1pk, o2pk);
	};

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
						reducedIndexes.get(0).getIndexKey().discriminator() instanceof ReferenceKey rk &&
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
				final EntityIndexKey entityIndexKey = new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceName);
				final Optional<Index<EntityIndexKey>> referencedEntityTypeIndex = orderByVisitor.getIndex(entityIndexKey);

				indexes = Stream.concat(
					indexes,
					referencedEntityTypeIndex.map(
							it -> ((ReferencedTypeEntityIndex) it).getAllPrimaryKeys()
								.stream()
								.mapToObj(
									refPk -> orderByVisitor.getIndex(
											new EntityIndexKey(
												EntityIndexType.REFERENCED_ENTITY,
												new ReferenceKey(referenceName, refPk)
											)
										)
										.orElse(null)
								)
								.map(ReducedEntityIndex.class::cast)
								.filter(Objects::nonNull)
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
	 * @param orderByVisitor The visitor containing the order-by constraints, locale, query context, and processing scope.
	 * @param referenceSchema The schema contract defining the referenced entity type and relationships to be used for sorting.
	 * @param ordering An array of {@link OrderConstraint} specifying the constraints to determine the sort order.
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
	 * @param orderByVisitor The visitor handling the query context, locale, and processing scope for sorting and traversal.
	 * @param referenceSchema The schema contract defining the entity references and relationships for sorting.
	 * @param traverseByEntityProperty An array of {@link OrderConstraint}, specifying the constraints for traversing and sorting.
	 * @param traversalMode The traversal mode dictating how the hierarchy nodes are processed.
	 * @param referenceIndexIds A formula providing the set of indices to be traversed and sorted.
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
					final int[] output = new int[input.length];
					final Formula filteringFormula = FormulaFactory.and(referenceIndexIds, new ConstantFormula(new BaseBitmap(input)));
					final int sortedPeak = sorter.sortAndSlice(
						filteringFormula,
						0, input.length, output, 0
					);
					Assert.isPremiseValid(
						sortedPeak == filteringFormula.compute().size(),
						"Unexpected number of sorted output: " + sortedPeak
					);
					return output;
				}
			};
		}

		final Set<Scope> allowedScopes = orderByVisitor.getProcessingScope().getScopes();
		IntStream result = IntStream.empty();
		for (Scope scope : Scope.values()) {
			if (allowedScopes.contains(scope)) {
				final Bitmap scopedResult = orderByVisitor.getGlobalEntityIndexIfExists(referenceSchema.getReferencedEntityType(), scope)
					.map(it -> it.listHierarchyNodesFromRoot(traversalMode, levelSorter))
					.orElse(EmptyBitmap.INSTANCE);
				result = IntStream.concat(result, IntStream.of(scopedResult.getArray()));
			}
		}
		return result;
	}

	/**
	 * Returns a stream of sorted primary keys for reduced index entities based on the specified order constraints.
	 * This method evaluates the provided order constraints and applies the appropriate sorting mechanism to
	 * produce the desired order of primary keys.
	 *
	 * @param orderByVisitor The visitor used to handle the order-by constraints, query context, and processing scope.
	 * @param referenceSchema The schema contract defining the referenced entity properties and relationships for sorting.
	 * @param pickFirstByEntityProperty An array of {@link OrderConstraint} that specifies the constraints for sorting by entity properties.
	 * @param referenceIndexIds A formula that provides the set of reference indices to be sorted.
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

			final int referenceIndexCount = referenceIndexIds.compute().size();
			final int[] result = new int[referenceIndexCount];
			final int sortedPeak = sorter.sortAndSlice(referenceIndexIds, 0, referenceIndexCount, result, 0);
			Assert.isPremiseValid(sortedPeak == referenceIndexCount, "Unexpected number of sorted indexes: " + sortedPeak);
			return IntStream.of(result);
		}
	}

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull ReferenceProperty referenceProperty, @Nonnull OrderByVisitor orderByVisitor) {
		final String referenceName = referenceProperty.getReferenceName();
		final EntitySchemaContract entitySchema = orderByVisitor.getSchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
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
			final IntObjectMap<ReducedEntityIndex> reducedIndexesMap = new IntObjectHashMap<>(referenceIndexes.size());
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (ReducedEntityIndex referenceIndex : referenceIndexes) {
				final ReferenceKey discriminator = (ReferenceKey) Objects.requireNonNull(referenceIndex.getIndexKey().discriminator());
				final int referencedPk = discriminator.primaryKey();
				writer.add(referencedPk);
				reducedIndexesMap.put(referencedPk, referenceIndex);
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
				.toArray(ReducedEntityIndex[]::new);

			orderByVisitor.executeInContext(
				sortedReducedIndexes,
				referenceSchema,
				null,
				processingScope.withReferenceSchemaAccessor(referenceName),
				new MergeModeDefinition(mergeMode, orderingSpecificationRef.isEmpty()),
				() -> {
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
					return null;
				}
			);

		}
		return Stream.empty();
	}

}
