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

package io.evitadb.core.query.sort.reference.translator;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.order.EntityPrimaryKeyNatural;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.api.query.order.ReferenceOrderingSpecification;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.order.TraverseByEntityProperty;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.translator.reference.HavingTranslatorHelper;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.MergeModeDefinition;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.reference.sorter.PickFirstReducedIndexResolver;
import io.evitadb.core.query.sort.reference.sorter.SequentialSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
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
	 * Planning-time index array of a `pickFirst` ordering, whose indexes are resolved at execution time instead.
	 */
	private static final ReducedEntityIndex[] EMPTY_REDUCED_INDEXES = new ReducedEntityIndex[0];
	/**
	 * All scopes in their declaration order - live first; cached, since {@link Scope#values()} copies the array.
	 */
	private static final Scope[] SCOPES = Scope.values();
	/**
	 * Method locates all {@link EntityIndex} from the resolved list of {@link TargetIndexes} which were identified
	 * by the {@link IndexSelectionVisitor}. The list is expected to be much smaller than the full list computed
	 * in {@link #selectFullEntityIndexSet(OrderByVisitor, String)}. Only the indexes of the scopes the ordering sorts
	 * are returned - the candidate set follows the filter, which may select owners of other scopes as well; an empty
	 * result makes the caller fall back to the whole family of the ordering scopes.
	 */
	@Nonnull
	private static List<ReducedEntityIndex> selectReducedEntityIndexSet(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull String referenceName
	) {
		for (TargetIndexes<?> targetIndex : orderByVisitor.getTargetIndexes()) {
			// the constraint is tested BEFORE the indexes are asked for. A candidate's index list may still be
			// unresolved at this point, and resolving it costs one index object per partition the reference
			// advertises - so identifying the reference from the already-known constraint keeps a query that
			// orders by one reference from materialising every other reference's candidates on the way past.
			if (
				ReducedEntityIndex.class.equals(targetIndex.getIndexType()) &&
					namesReference(targetIndex.getRepresentedConstraint(), referenceName)
			) {
				//noinspection unchecked
				final List<ReducedEntityIndex> reducedIndexes = (List<ReducedEntityIndex>) targetIndex.getIndexes();
				if (
					!reducedIndexes.isEmpty() &&
						reducedIndexes.get(0).getIndexKey().discriminator() instanceof RepresentativeReferenceKey rk &&
						referenceName.equals(rk.referenceName())
				) {
					// the filter may select owners of scopes the ordering does not sort - their partitions must not
					// open blocks, or those owners would be placed instead of being handed to the next sorter
					return retainScopes(reducedIndexes, orderByVisitor.getProcessingScope().getScopes());
				}
			}
		}
		return Collections.emptyList();
	}

	/**
	 * Tells whether the constraint a {@link TargetIndexes} was built for targets the passed reference.
	 *
	 * Used as a cheap pre-filter for the index-set lookup above: it answers from the constraint alone, so a
	 * candidate belonging to a different reference is skipped without its indexes ever being resolved.
	 *
	 * It is AND-ed with the discriminator check below rather than refined by it, so a constraint shape not
	 * recognised here skips the candidate outright and {@link #selectReducedEntityIndexSet} finds nothing. What
	 * keeps that from changing the rows is the caller's fallback to {@link #selectFullEntityIndexSet}, which
	 * answers the same query from the unreduced indexes - so the cost is a slower sort, not a wrong index set.
	 *
	 * @param representedConstraint the constraint the index set answers, `null` for sets built without one
	 * @param referenceName         the reference being ordered by
	 * @return `true` when the constraint targets that reference
	 */
	private static boolean namesReference(
		@Nullable FilterConstraint representedConstraint,
		@Nonnull String referenceName
	) {
		if (representedConstraint instanceof final ReferenceHaving referenceHaving) {
			return referenceName.equals(referenceHaving.getReferenceName());
		} else if (representedConstraint instanceof final HierarchyFilterConstraint hierarchyFilterConstraint) {
			return hierarchyFilterConstraint.getReferenceName().map(referenceName::equals).orElse(false);
		} else {
			return false;
		}
	}

	/**
	 * Keeps only the reduced indexes of the passed scopes, in their original order. The list is returned as it is when
	 * every index belongs to one of the scopes - the common case of an ordering that sorts every scope the filter
	 * selects.
	 *
	 * @param reducedIndexes the candidate reduced indexes
	 * @param scopes         the scopes the ordering sorts
	 * @return the indexes of the passed scopes, possibly empty
	 */
	@Nonnull
	private static List<ReducedEntityIndex> retainScopes(
		@Nonnull List<ReducedEntityIndex> reducedIndexes,
		@Nonnull Set<Scope> scopes
	) {
		int retained = 0;
		for (ReducedEntityIndex reducedIndex : reducedIndexes) {
			if (scopes.contains(reducedIndex.getIndexKey().scope())) {
				retained++;
			}
		}
		if (retained == reducedIndexes.size()) {
			return reducedIndexes;
		}
		final List<ReducedEntityIndex> result = new ArrayList<>(retained);
		for (ReducedEntityIndex reducedIndex : reducedIndexes) {
			if (scopes.contains(reducedIndex.getIndexKey().scope())) {
				result.add(reducedIndex);
			}
		}
		return result;
	}

	/**
	 * Returns true when at least one of the processed scopes holds a non-empty {@link ReferencedTypeEntityIndex} of
	 * the given reference, i.e. when at least one owner has a row of the reference that ordering could use.
	 *
	 * @param orderByVisitor the visitor providing the processing scope and the index access
	 * @param referenceName  the name of the reference
	 * @return true if any processed scope contains a reduced index of the referenced entity family
	 */
	private static boolean hasAnyReducedIndex(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull String referenceName
	) {
		for (Scope scope : orderByVisitor.getProcessingScope().getScopes()) {
			final boolean nonEmpty = orderByVisitor
				.getIndexIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName),
					ReferencedTypeEntityIndex.class
				)
				.map(it -> !it.getAllPrimaryKeys().isEmpty())
				.orElse(false);
			if (nonEmpty) {
				return true;
			}
		}
		return false;
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
	 * Lists the referenced entities in the order of the traversal of the target hierarchy. The tree of every scope that
	 * holds at least one of the referenced entities is walked on its own, the live tree first - a referenced entity is
	 * positioned in the tree of its own scope, whatever the scope of the owners referencing it (an archived owner of a
	 * live target is ordered by the live tree). The scopes the ordering sorts decide only which owners take part; they
	 * were applied to the reduced indexes the referenced entities come from already.
	 *
	 * The siblings of each level follow the requested order. A nested order is resolved by one
	 * {@link NestedContextSorter} over the ordering's scopes shared by the walks of all trees, so a tree of a scope the
	 * ordering does not sort keeps its siblings in primary key order - as the flat targets and the `pickFirst`
	 * ordering of such a scope do.
	 *
	 * @param orderByVisitor           the visitor of the planned query
	 * @param referenceSchema          the reference being ordered by
	 * @param traverseByEntityProperty the order of the siblings in the tree
	 * @param traversalMode            depth-first or breadth-first walk of the tree
	 * @param referenceIndexIds        the primary keys of the referenced entities that have a reduced index
	 * @return the primary keys of the walked hierarchy nodes in traversal order, live tree first
	 */
	@Nonnull
	private static IntStream getTraversedAndSortedReducedIndexPrimaryKeys(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull OrderConstraint[] traverseByEntityProperty,
		@Nonnull TraversalMode traversalMode,
		@Nonnull Formula referenceIndexIds
	) {
		final PersistentRoaringBitmap targets = RoaringBitmapBackedBitmap.getRoaringBitmap(referenceIndexIds.compute());
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		// one nested sorter serves the walks of all trees - created on first use, only when a tree is walked at all
		final NestedContextSorter[] nestedSorter = new NestedContextSorter[1];
		final Supplier<NestedContextSorter> nestedSorterSupplier = () -> {
			if (nestedSorter[0] == null) {
				nestedSorter[0] = createNestedContextSorter(orderByVisitor, referenceSchema, traverseByEntityProperty);
			}
			return nestedSorter[0];
		};
		IntStream result = IntStream.empty();
		for (Scope scope : SCOPES) {
			final Optional<GlobalEntityIndex> treeIndex = orderByVisitor.getGlobalEntityIndexIfExists(
				referencedEntityType, scope
			);
			if (treeIndex.isPresent()) {
				final GlobalEntityIndex tree = treeIndex.get();
				// only the referenced entities living in this scope are nodes of its tree - asking the tree about the
				// others would fail on a node it does not know
				final PersistentRoaringBitmap targetsInTree = PersistentRoaringBitmap.and(
					targets, RoaringBitmapBackedBitmap.getRoaringBitmap(tree.getAllPrimaryKeys())
				);
				if (!targetsInTree.isEmpty()) {
					final Bitmap walkedNodes = tree.listHierarchyNodesFromRoot(
						traversalMode,
						createLevelSorter(traverseByEntityProperty, nestedSorterSupplier, tree, targetsInTree)
					);
					result = IntStream.concat(result, IntStream.of(walkedNodes.getArray()));
				}
			}
		}
		return result;
	}

	/**
	 * Creates the operator ordering the siblings of one level of the tree walk. The primary key order needs no sorter;
	 * any other order sorts the siblings that are referenced entities or their ancestors by the nested sorter - the
	 * other siblings, whose subtrees hold no referenced entity, are left out of the walk.
	 *
	 * @param traverseByEntityProperty the order of the siblings
	 * @param nestedSorter             supplier of the nested sorter resolving an order other than the primary key one
	 * @param tree                     the global index of the walked tree
	 * @param targetsInTree            the referenced entities living in the walked tree, never empty
	 * @return the operator ordering the siblings of one level
	 */
	@Nonnull
	private static UnaryOperator<int[]> createLevelSorter(
		@Nonnull OrderConstraint[] traverseByEntityProperty,
		@Nonnull Supplier<NestedContextSorter> nestedSorter,
		@Nonnull GlobalEntityIndex tree,
		@Nonnull PersistentRoaringBitmap targetsInTree
	) {
		if (traverseByEntityProperty.length == 1 && traverseByEntityProperty[0] instanceof EntityPrimaryKeyNatural epkn) {
			return epkn.getOrderDirection() == OrderDirection.ASC ?
				UnaryOperator.identity() : ArrayUtils::reverse;
		} else {
			// the referenced entities with all their ancestors, resolved once per walk on the first non-empty level
			final Bitmap[] nodesWithParents = new Bitmap[1];
			return input -> {
				if (input.length == 0) {
					return ArrayUtils.EMPTY_INT_ARRAY;
				} else {
					if (nodesWithParents[0] == null) {
						nodesWithParents[0] = tree.listNodesIncludingParents(new BaseBitmap(targetsInTree));
					}
					return nestedSorter.get().sortAndSlice(
						FormulaFactory.and(
							new ConstantFormula(nodesWithParents[0]),
							new ConstantFormula(new BaseBitmap(input))
						).compute()
					);
				}
			};
		}
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

	/**
	 * Selects the reduced indexes of the reference at planning time and orders them by their referenced entities.
	 * The set is the candidate set of a `referenceHaving` / `hierarchyWithin` on the same reference when the index
	 * selection built one, the whole family of the reference otherwise - in both cases restricted to the scopes the
	 * ordering sorts. Indexes sharing one referenced entity are grouped by their representative reference key and, inside
	 * a key, by scope (live first), so the indexes of one referenced entity, and of each of its keys, are contiguous; the
	 * keys follow {@link RepresentativeReferenceKey#STRICT_COMPARATOR} for a traversal and the order the selection met
	 * them otherwise (see {@link #appendGroupedByRepresentativeKey(List, boolean, ReducedEntityIndex[], int)}).
	 *
	 * This is the index set of the block-by-block orderings - `traverseByEntityProperty` and chain attributes, whose
	 * blocks follow the narrowed set. A `pickFirst` ordering of comparable values resolves its indexes at execution
	 * time instead (see {@link PickFirstReducedIndexResolver}).
	 *
	 * @param orderByVisitor           the visitor of the planned query
	 * @param referenceName            the reference being ordered by
	 * @param targetOrder              orders the primary keys of the referenced entities
	 * @param inRepresentativeKeyOrder whether the keys of one referenced entity follow the representative order (a
	 *                                 traversal) rather than the order they were met in (the planning-time indexes of
	 *                                 a `pickFirst` ordering)
	 * @return the ordered reduced indexes, empty when the reference has none
	 */
	@Nonnull
	private static ReducedEntityIndex[] selectPlanningReducedIndexes(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull String referenceName,
		@Nonnull Function<Formula, IntStream> targetOrder,
		boolean inRepresentativeKeyOrder
	) {
		final List<ReducedEntityIndex> reducedEntityIndexSet = selectReducedEntityIndexSet(orderByVisitor, referenceName);
		final List<ReducedEntityIndex> referenceIndexes = reducedEntityIndexSet.isEmpty() ?
			selectFullEntityIndexSet(orderByVisitor, referenceName) :
			reducedEntityIndexSet;
		if (referenceIndexes.isEmpty()) {
			return EMPTY_REDUCED_INDEXES;
		}
		final IntObjectMap<List<ReducedEntityIndex>> reducedIndexesMap = new IntObjectHashMap<>(referenceIndexes.size());
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (ReducedEntityIndex referenceIndex : referenceIndexes) {
			final ReferenceKey discriminator = Objects.requireNonNull((RepresentativeReferenceKey) referenceIndex.getIndexKey().discriminator()).referenceKey();
			final int referencedPk = discriminator.primaryKey();
			List<ReducedEntityIndex> sameTarget = reducedIndexesMap.get(referencedPk);
			if (sameTarget == null) {
				sameTarget = new ArrayList<>(2);
				reducedIndexesMap.put(referencedPk, sameTarget);
				writer.add(referencedPk);
			}
			sameTarget.add(referenceIndex);
		}
		final int[] orderedTargets = targetOrder.apply(new ConstantFormula(new BaseBitmap(writer.get()))).toArray();
		final ReducedEntityIndex[] result = new ReducedEntityIndex[referenceIndexes.size()];
		int peak = 0;
		for (int target : orderedTargets) {
			// removed, so that a target listed twice by the target order still contributes its indexes once
			final List<ReducedEntityIndex> sameTarget = reducedIndexesMap.remove(target);
			if (sameTarget != null) {
				if (sameTarget.size() == 1) {
					result[peak++] = sameTarget.get(0);
				} else {
					peak = appendGroupedByRepresentativeKey(sameTarget, inRepresentativeKeyOrder, result, peak);
				}
			}
		}
		return peak == result.length ? result : Arrays.copyOf(result, peak);
	}

	/**
	 * Appends the reduced indexes of one referenced entity grouped by their representative reference keys, so that
	 * the indexes of one key lie next to each other, live first. The keys follow either
	 * {@link RepresentativeReferenceKey#STRICT_COMPARATOR} (`inRepresentativeKeyOrder`) or the order in which the
	 * selection met them.
	 *
	 * The representative order carries the rule of a traversal for rows sharing one target: a traversal block is the
	 * whole target, and each owner is represented in it by its first row - in this order - that carries the sorted
	 * value. The index route realizes the rule by letting the block's providers claim their owners in exactly this
	 * order, the prefetch route by picking the row in the same order (see
	 * `EntityReferenceSensitiveComparator#getFirstValuedReference`). A chain under an implicit `pickFirst` walks the
	 * same planning-time indexes without blocks and is left in the order the keys were met - the order it had before
	 * the traversal adopted the representative one.
	 *
	 * Keys are told apart by their own equality: values comparing equal may still be different keys (`BigDecimal` 1.0
	 * and 1.00), and each has an index of its own. The representative order tells such keys apart by the values
	 * themselves, so their order never depends on the order the selection met them in - the prefetch route, which never
	 * sees that order, picks its row the same way. Two contiguity invariants rest on this append: the indexes of the
	 * whole referenced entity lie together, which `MergedSortedRecordsSupplierSorter#createTargetSlices` requires to
	 * slice the providers of a block and refuses to do without, and so do the indexes of one key, which the chain
	 * comparator's lookup of a key's providers (`MergedSortedRecordsSupplierSorter#createSortedRecordsOffsets`) requires
	 * and a repeated key would silently break. A chain of a key relies on its live chain preceding the archived one.
	 *
	 * Costs one pass over the `m` indexes of the referenced entity plus, in representative order, a sort of its `k`
	 * distinct keys (`O(k log k)`); reached only by a referenced entity with more than one index - a reference
	 * allowing duplicates, or one entity referenced from both scopes.
	 *
	 * @param sameTarget               the reduced indexes of one referenced entity in the order they were selected in
	 * @param inRepresentativeKeyOrder whether the keys follow the representative order rather than the order they
	 *                                 were met in
	 * @param result                   the array the indexes are appended to
	 * @param peak                     the first free position of `result`
	 * @return the first free position of `result` after the append
	 */
	static int appendGroupedByRepresentativeKey(
		@Nonnull List<ReducedEntityIndex> sameTarget,
		boolean inRepresentativeKeyOrder,
		@Nonnull ReducedEntityIndex[] result,
		int peak
	) {
		final int size = sameTarget.size();
		// one slot per scope for every key, keys in the order of their first index - one pass over the indexes
		final LinkedHashMap<RepresentativeReferenceKey, ReducedEntityIndex[]> byKey =
			CollectionUtils.createLinkedHashMap(size);
		for (int i = 0; i < size; i++) {
			final ReducedEntityIndex index = sameTarget.get(i);
			final ReducedEntityIndex[] scopeSlots = byKey.computeIfAbsent(
				index.getRepresentativeReferenceKey(), key -> new ReducedEntityIndex[SCOPES.length]
			);
			final int slot = index.getIndexKey().scope().ordinal();
			Assert.isPremiseValid(
				scopeSlots[slot] == null,
				() -> "Two reduced indexes share the key `" + index.getRepresentativeReferenceKey() +
					"` in scope " + index.getIndexKey().scope() + "!"
			);
			scopeSlots[slot] = index;
		}
		int resultPeak = peak;
		if (inRepresentativeKeyOrder && byKey.size() > 1) {
			final List<Entry<RepresentativeReferenceKey, ReducedEntityIndex[]>> entries = new ArrayList<>(byKey.entrySet());
			// the keys are distinct, and the strict order tells apart even those whose values compare equal
			entries.sort(Entry.comparingByKey(RepresentativeReferenceKey.STRICT_COMPARATOR));
			for (Entry<RepresentativeReferenceKey, ReducedEntityIndex[]> entry : entries) {
				resultPeak = appendScopeSlots(entry.getValue(), result, resultPeak);
			}
		} else {
			for (ReducedEntityIndex[] scopeSlots : byKey.values()) {
				resultPeak = appendScopeSlots(scopeSlots, result, resultPeak);
			}
		}
		return resultPeak;
	}

	/**
	 * Appends one representative key's scope slots to the result, live first - a slot left `null` (a scope the key
	 * has no index in) is skipped.
	 *
	 * @param scopeSlots one representative key's index per scope, `null` for a scope it has none in
	 * @param result     the array the indexes are appended to
	 * @param peak       the first free position of `result`
	 * @return the first free position of `result` after the append
	 */
	private static int appendScopeSlots(
		@Nonnull ReducedEntityIndex[] scopeSlots,
		@Nonnull ReducedEntityIndex[] result,
		int peak
	) {
		// slots follow the order of the scopes - live first
		for (ReducedEntityIndex index : scopeSlots) {
			if (index != null) {
				result[peak++] = index;
			}
		}
		return peak;
	}

	/**
	 * Creates the resolver of the reduced indexes a `pickFirst` ordering walks. The ordering of the targets is
	 * prepared here, at planning time: the plain `entityPrimaryKeyNatural` needs no sorter at all, any other
	 * specification gets a {@link NestedContextSorter} over the referenced collection that the resolver applies to the
	 * targets of each selection.
	 *
	 * @param orderByVisitor            the visitor of the planned query
	 * @param referenceSchema           the reference being ordered by
	 * @param pickFirstByEntityProperty the ordering of the targets
	 * @param planningIndexes           supplier of the ordered reduced indexes for consumers that need them at
	 *                                  planning time
	 * @return the resolver
	 */
	@Nonnull
	private static PickFirstReducedIndexResolver createPickFirstIndexResolver(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull OrderConstraint[] pickFirstByEntityProperty,
		@Nonnull Supplier<ReducedEntityIndex[]> planningIndexes
	) {
		final Set<Scope> allowedScopes = orderByVisitor.getProcessingScope().getScopes();
		final Scope[] scopes = Arrays.stream(Scope.values())
			.filter(allowedScopes::contains)
			.toArray(Scope[]::new);
		if (pickFirstByEntityProperty.length == 1 && pickFirstByEntityProperty[0] instanceof EntityPrimaryKeyNatural epkn) {
			return new PickFirstReducedIndexResolver(
				orderByVisitor.getQueryContext(), referenceSchema, scopes, null,
				epkn.getOrderDirection() == OrderDirection.DESC, planningIndexes
			);
		} else {
			return new PickFirstReducedIndexResolver(
				orderByVisitor.getQueryContext(), referenceSchema, scopes,
				createNestedContextSorter(orderByVisitor, referenceSchema, pickFirstByEntityProperty),
				false, planningIndexes
			);
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
			// a scope indexed without the entity component holds no reduced entity index, so every owner in it would
			// sort as if it had no reference at all. Checked here for every ordered scope rather than at the lookup
			// below, because the index set may come from index selection instead - narrowed by an `inScope(...)` to
			// other scopes, while the rows of this one are still sorted
			HavingTranslatorHelper.assertEntityComponentIndexed(
				entitySchema, referenceSchema, scope, processingScope.getScopes()
			);
		}
		// every scope passed the check above, so ordering by this reference genuinely depends on `indexed()` in all
		// of them - the widest dependency the surface reports, since dropping it takes the reduced index family too
		orderByVisitor.getQueryContext().recordRequestedReferenceCapability(
			entitySchema, referenceName, Capability.INDEXED, processingScope.getScopes()
		);
		final boolean referencedEntityHierarchical = referenceSchema.isReferencedEntityTypeManaged() &&
			orderByVisitor.getSchema(referenceSchema.getReferencedEntityType()).isWithHierarchy();

		final Optional<ReferenceOrderingSpecification> orderingSpecificationRef = referenceProperty.getReferenceOrderingSpecification();
		final ReferenceOrderingSpecification orderingSpecification = orderingSpecificationRef
			.orElseGet(
				() -> referencedEntityHierarchical ?
					traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)) :
					pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC))
			);

		if (orderingSpecification instanceof PickFirstByEntityProperty pfbep) {
			if (!hasAnyReducedIndex(orderByVisitor, referenceName)) {
				// no owner has a row of this reference in the processed scopes - there is nothing to sort by, and the
				// nested constraints must not be planned (single-index translators require an index to exist)
				return Stream.empty();
			}
			// the reduced indexes a pick-first ordering walks depend on the selected owners only - every row of a
			// selected owner takes part, whatever the filter says about the same reference - so they are resolved
			// at execution time and the candidates of a `referenceHaving` in the filter are deliberately ignored
			orderByVisitor.executeInContext(
				EMPTY_REDUCED_INDEXES,
				referenceSchema,
				null,
				processingScope.withReferenceSchemaAccessor(referenceName),
				new MergeModeDefinition(MergeMode.APPEND_FIRST, orderingSpecificationRef.isEmpty()),
				createPickFirstIndexResolver(
					orderByVisitor, referenceSchema, pfbep.getChildren(),
					() -> selectPlanningReducedIndexes(
						orderByVisitor,
						referenceName,
						referenceIndexIds -> getSortedReducedIndexPrimaryKeys(
							orderByVisitor, referenceSchema, pfbep.getChildren(), referenceIndexIds
						),
						// outside the traversal: the keys of one referenced entity stay in the order they were met
						false
					)
				),
				() -> {
					traverseChildConstraints(referenceProperty, orderByVisitor);
					return null;
				}
			);
			return Stream.empty();
		} else if (!(orderingSpecification instanceof TraverseByEntityProperty)) {
			throw new GenericEvitaInternalError("Expected initialized ordering specification at least by defaults!");
		}

		final TraverseByEntityProperty tbep = (TraverseByEntityProperty) orderingSpecification;
		final ReducedEntityIndex[] sortedReducedIndexes = selectPlanningReducedIndexes(
			orderByVisitor,
			referenceName,
			referenceIndexIds -> referencedEntityHierarchical ?
				getTraversedAndSortedReducedIndexPrimaryKeys(
					orderByVisitor, referenceSchema, tbep.getChildren(), tbep.getTraversalMode(), referenceIndexIds
				) :
				getSortedReducedIndexPrimaryKeys(
					orderByVisitor, referenceSchema, tbep.getChildren(), referenceIndexIds
				),
			true
		);
		if (sortedReducedIndexes.length == 0) {
			return Stream.empty();
		}

		// split the indexes into blocks of one referenced entity each - the indexes of every representative key and of
		// both scopes of one referenced entity form one block, in which each owner is represented by its first valued
		// row in representative key order, and whose owners the index sorters merge by value (chains excepted, see
		// MergedSortedRecordsSupplierSorter) and the prefetch comparators sort together
		final List<ReducedEntityIndex[]> atomicBlocks = new ArrayList<>(sortedReducedIndexes.length);
		int start = 0;
		ReferenceKey referenceKey = null;
		for (int i = 0; i < sortedReducedIndexes.length; i++) {
			final ReferenceKey srpReferenceKey = sortedReducedIndexes[i].getReferenceKey();
			if (referenceKey != null && !referenceKey.equalsInGeneral(srpReferenceKey)) {
				atomicBlocks.add(Arrays.copyOfRange(sortedReducedIndexes, start, i));
				start = i;
			}
			referenceKey = srpReferenceKey;
		}
		atomicBlocks.add(Arrays.copyOfRange(sortedReducedIndexes, start, sortedReducedIndexes.length));

		final List<Sorter> sorters = orderByVisitor.executeInContext(
			sortedReducedIndexes,
			referenceSchema,
			null,
			processingScope.withReferenceSchemaAccessor(referenceName),
			new MergeModeDefinition(MergeMode.APPEND_ALL, orderingSpecificationRef.isEmpty()),
			null,
			() -> orderByVisitor.collectIsolatedSorters(
				() -> traverseChildConstraints(referenceProperty, orderByVisitor)
			)
		);

		return Stream.of(
			new SequentialSorter(atomicBlocks.toArray(ReducedEntityIndex[][]::new), sorters)
		);
	}

}
