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

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.EntityPrimaryKeyNatural;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link ReferenceProperty} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferencePropertyTranslator implements OrderingConstraintTranslator<ReferenceProperty>, SelfTraversingTranslator {
	private static final Comparator<EntityIndex> DEFAULT_COMPARATOR = (o1, o2) -> {
		final int o1pk = ((ReferenceKey) o1.getIndexKey().discriminator()).primaryKey();
		final int o2pk = ((ReferenceKey) o2.getIndexKey().discriminator()).primaryKey();
		return Integer.compare(o1pk, o2pk);
	};
	private static final EntityIndex[] EMPTY_INDEXES = new EntityIndex[0];

	/**
	 * Method locates all {@link EntityIndex} instances that are related to the given reference name. The list is
	 * resolved from {@link ReferencedTypeEntityIndex}.
	 */
	@Nonnull
	private static EntityIndex[] selectFullEntityIndexSet(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull String referenceName,
		@Nonnull Comparator<EntityIndex> indexComparator
	) {
		final EntityIndexKey entityIndexKey = new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceName);
		final Optional<Index<EntityIndexKey>> referencedEntityTypeIndex = orderByVisitor.getIndex(entityIndexKey);

		return referencedEntityTypeIndex.map(
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
					.map(EntityIndex.class::cast)
					.filter(Objects::nonNull)
					.sorted(indexComparator)
					.toArray(EntityIndex[]::new)
			)
			.orElse(EMPTY_INDEXES);
	}

	/**
	 * This method generates a hierarchy-aware comparator for entity indices based on the provided
	 * {@link OrderByVisitor} and {@link ReferenceSchemaContract}. It iterates through the scopes
	 * provided by the OrderByVisitor and retrieves corresponding global entity indices to build
	 * a comparator that sorts entity indices according to their hierarchical relationships.
	 *
	 * @param orderByVisitor  The visitor containing the order by constraints and scopes used to retrieve
	 *                        global entity indices.
	 * @param referenceSchema The schema contract defining the referenced entity types and relationships.
	 * @return A {@link Comparator} for {@link EntityIndex} objects that sorts them based on hierarchical
	 * relationships, or a default comparator if no hierarchical relationships are found.
	 */
	private static Comparator<EntityIndex> getHierarchyComparator(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		/* TODO JNO - tady se asi nekoná to procházení do hloubky, co je dokumentované */
		final Set<Scope> allowedScopes = orderByVisitor.getProcessingScope().getScopes();
		Comparator<EntityIndex> comparator = null;
		for (Scope scope : Scope.values()) {
			if (allowedScopes.contains(scope)) {
				final Optional<GlobalEntityIndex> globalEntityIndex = orderByVisitor.getGlobalEntityIndexIfExists(referenceSchema.getReferencedEntityType(), scope);
				if (globalEntityIndex.isPresent()) {
					comparator = comparator == null ?
						ReferencePropertyTranslator.getHierarchyComparator(globalEntityIndex.get()) :
						comparator.thenComparing(ReferencePropertyTranslator.getHierarchyComparator(globalEntityIndex.get()));
				}
			}
		}
		return comparator == null ? DEFAULT_COMPARATOR : comparator;
	}

	/**
	 * This method generates a hierarchy-aware comparator for entity indices based on the provided
	 * {@link OrderByVisitor} and {@link ReferenceSchemaContract}. It iterates through the scopes
	 * provided by the OrderByVisitor and retrieves corresponding global entity indices to build
	 * a comparator that sorts entity indices according to their hierarchical relationships.
	 *
	 * @param orderByVisitor  The visitor containing the order by constraints and scopes used to retrieve
	 *                        global entity indices.
	 * @param referenceSchema The schema contract defining the referenced entity types and relationships.
	 * @return A {@link Comparator} for {@link EntityIndex} objects that sorts them based on hierarchical
	 * relationships, or a default comparator if no hierarchical relationships are found.
	 */
	private static Comparator<EntityIndex> getNonHierarchyComparator(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull OrderConstraint[] traverseByEntityProperty
	) {
		/* TODO JNO - implementovat odlišné traverzování */
		final Set<Scope> allowedScopes = orderByVisitor.getProcessingScope().getScopes();
		Comparator<EntityIndex> comparator = null;
		for (Scope scope : Scope.values()) {
			if (allowedScopes.contains(scope)) {
				final Optional<GlobalEntityIndex> globalEntityIndex = orderByVisitor.getGlobalEntityIndexIfExists(referenceSchema.getReferencedEntityType(), scope);
				if (globalEntityIndex.isPresent()) {
					comparator = comparator == null ?
						ReferencePropertyTranslator.getHierarchyComparator(globalEntityIndex.get()) :
						comparator.thenComparing(ReferencePropertyTranslator.getHierarchyComparator(globalEntityIndex.get()));
				}
			}
		}
		return comparator == null ? DEFAULT_COMPARATOR : comparator;
	}

	/**
	 * This method generates a hierarchy-aware comparator for entity indices based on the provided
	 * {@link OrderByVisitor} and {@link ReferenceSchemaContract}. It iterates through the scopes
	 * provided by the OrderByVisitor and retrieves corresponding global entity indices to build
	 * a comparator that sorts entity indices according to their hierarchical relationships.
	 *
	 * @param orderByVisitor  The visitor containing the order by constraints and scopes used to retrieve
	 *                        global entity indices.
	 * @param referenceSchema The schema contract defining the referenced entity types and relationships.
	 * @return A {@link Comparator} for {@link EntityIndex} objects that sorts them based on hierarchical
	 * relationships, or a default comparator if no hierarchical relationships are found.
	 */
	private static Comparator<EntityIndex> getHierarchyComparator(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull OrderConstraint[] traverseByEntityProperty
	) {
		/* TODO JNO - implementovat odlišné traverzování */
		final Set<Scope> allowedScopes = orderByVisitor.getProcessingScope().getScopes();
		Comparator<EntityIndex> comparator = null;
		for (Scope scope : Scope.values()) {
			if (allowedScopes.contains(scope)) {
				final Optional<GlobalEntityIndex> globalEntityIndex = orderByVisitor.getGlobalEntityIndexIfExists(referenceSchema.getReferencedEntityType(), scope);
				if (globalEntityIndex.isPresent()) {
					comparator = comparator == null ?
						ReferencePropertyTranslator.getHierarchyComparator(globalEntityIndex.get()) :
						comparator.thenComparing(ReferencePropertyTranslator.getHierarchyComparator(globalEntityIndex.get()));
				}
			}
		}
		return comparator == null ? DEFAULT_COMPARATOR : comparator;
	}

	/**
	 * Method locates all {@link EntityIndex} from the resolved list of {@link TargetIndexes} which were identified
	 * by the {@link IndexSelectionVisitor}. The list is expected to be much smaller than the full list computed
	 * in {@link #selectFullEntityIndexSet(OrderByVisitor, String, Comparator)}.
	 */
	@Nonnull
	private static EntityIndex[] selectReducedEntityIndexSet(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull String referenceName,
		@Nonnull Comparator<EntityIndex> indexComparator
	) {

		return orderByVisitor.getTargetIndexes()
			.stream()
			.flatMap(it -> it.getIndexes().stream())
			.filter(it -> it instanceof EntityIndex)
			.map(EntityIndex.class::cast)
			.filter(it -> !(it instanceof ReferencedTypeEntityIndex))
			.filter(
				it -> it.getIndexKey().discriminator() instanceof ReferenceKey referenceKey &&
					referenceKey.referenceName().equals(referenceName)
			)
			.sorted(indexComparator)
			.toArray(EntityIndex[]::new);
	}

	/**
	 * Method creates the comparator that allows to sort referenced primary keys by their presence in the hierarchy
	 * using deep traversal mechanism.
	 */
	@Nonnull
	private static Comparator<EntityIndex> getHierarchyComparator(@Nonnull GlobalEntityIndex entityIndex) {
		final Comparator<Integer> comparator = entityIndex.getHierarchyComparator();
		return (o1, o2) -> comparator.compare(
			Objects.requireNonNull((ReferenceKey) o1.getIndexKey().discriminator()).primaryKey(),
			Objects.requireNonNull((ReferenceKey) o2.getIndexKey().discriminator()).primaryKey()
		);
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

		final Comparator<EntityIndex> indexComparator = referenceProperty.getTraverseByEntityProperty()
			.map(tbep -> referencedEntityHierarchical ? getHierarchyComparator(orderByVisitor, referenceSchema, tbep.getChildren()) : getNonHierarchyComparator(orderByVisitor, referenceSchema, tbep.getChildren()))
			.orElseGet(() -> referencedEntityHierarchical ? getHierarchyComparator(orderByVisitor, referenceSchema) : DEFAULT_COMPARATOR);

		final EntityIndex[] reducedEntityIndexSet = selectReducedEntityIndexSet(orderByVisitor, referenceName, indexComparator);
		final EntityIndex[] referenceIndexes = ArrayUtils.isEmpty(reducedEntityIndexSet) ?
			selectFullEntityIndexSet(orderByVisitor, referenceName, indexComparator) :
			reducedEntityIndexSet;

		orderByVisitor.executeInContext(
			referenceIndexes,
			referenceSchema,
			null,
			processingScope.withReferenceSchemaAccessor(referenceName),
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
					}
					innerConstraint.accept(orderByVisitor);
				}
				return null;
			}
		);

		return Stream.empty();
	}

}
