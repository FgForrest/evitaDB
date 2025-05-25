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

package io.evitadb.core.query.filter.translator.facet;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.HierarchyNotIndexedException;
import io.evitadb.core.exception.ReferenceNotFacetedException;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.facet.CombinedFacetFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupAndFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link FacetHaving} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetHavingTranslator implements FilteringConstraintTranslator<FacetHaving>, SelfTraversingTranslator {

	/**
	 * Isolates and processes filtering constraints related to facet filtering for hierarchical and non-hierarchical references.
	 * The method identifies inclusion and exclusion constraints specific to hierarchical facet references and ensures that references
	 * are properly validated and managed within the given processing scope.
	 *
	 * @param filterByVisitor an instance of {@link FilterByVisitor} used for schema and processing scope management
	 * @param children        an array of {@link FilterConstraint} objects representing the constraints to be processed
	 * @param referenceSchema a {@link ReferenceSchemaContract} representing the reference schema to validate
	 * @return a {@link FacetFiltering} object bundling the main filtering constraint, and optional hierarchy inclusion/exclusion constraints
	 */
	@Nonnull
	private static FacetFiltering isolateFilteringConstraints(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull FilterConstraint[] children,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Set<Scope> scopes
	) {
		if (children.length == 1 && children[0] instanceof And and) {
			// if the only child is AND, we can use it directly
			return isolateFilteringConstraints(filterByVisitor, and.getChildren(), referenceSchema, scopes);
		} else {
			HierarchyHaving hierarchyIncludeConstraint = null;
			HierarchyExcluding hierarchyExcludeConstraint = null;
			IntSet excludedIndexes = null;
			EntitySchemaContract targetSchema = null;
			boolean isHierarchical = false;
			for (int i = 0; i < children.length; i++) {
				FilterConstraint child = children[i];
				if (child instanceof FacetIncludingChildren fic) {
					hierarchyIncludeConstraint = having(fic.getChildren());
					if (excludedIndexes == null) {
						targetSchema = assertReferenceIsHierarchical(filterByVisitor, referenceSchema, scopes);
						excludedIndexes = new IntHashSet(2);
						isHierarchical = true;
					}
					excludedIndexes.add(i);
				} else if (child instanceof FacetIncludingChildrenExcept fic) {
					hierarchyExcludeConstraint = excluding(fic.getChildren());
					if (excludedIndexes == null) {
						targetSchema = assertReferenceIsHierarchical(filterByVisitor, referenceSchema, scopes);
						excludedIndexes = new IntHashSet(2);
						isHierarchical = true;
					}
					excludedIndexes.add(i);
				}
			}

			final FilterBy mainFiltering = excludedIndexes == null ?
				filterBy(children) : createCopyExcludingIndexes(children, excludedIndexes);

			Assert.isTrue(
				mainFiltering.getChildrenCount() > 0,
				() -> "FacetHaving must contain at least one filter constraint!"
			);

			final HierarchySpecificationFilterConstraint[] hierarchyConstraints = Stream.of(
					(HierarchySpecificationFilterConstraint) hierarchyIncludeConstraint,
					hierarchyExcludeConstraint
				)
				.filter(Objects::nonNull)
				.toArray(HierarchySpecificationFilterConstraint[]::new);
			return new FacetFiltering(
				mainFiltering,
				isHierarchical ?
					parentNodeIds -> hierarchyWithin(
						referenceSchema.getName(),
						entityPrimaryKeyInSet(parentNodeIds),
						hierarchyConstraints
					) : null,
				targetSchema,
				scopes.stream()
					.map(scope -> filterByVisitor.getGlobalEntityIndexIfExists(referenceSchema.getReferencedEntityType(), scope))
					.filter(Optional::isPresent)
					.map(Optional::get)
					.toArray(EntityIndex[]::new)
			);
		}
	}

	/**
	 * Ensures that the provided reference is hierarchical and properly indexed in the given processing scope.
	 * This method verifies that:
	 *
	 * 1. The reference is associated with an entity type that is managed.
	 * 2. The referenced entity schema supports hierarchy.
	 * 3. The hierarchy is indexed in each scope of the current processing scope.
	 *
	 * @param filterByVisitor an instance of {@link FilterByVisitor} used for retrieving schemas and processing scopes
	 * @param referenceSchema a {@link ReferenceSchemaContract} representing the reference to validate
	 * @throws EntityIsNotHierarchicalException if the referenced entity is not hierarchical or its type is not managed
	 * @throws HierarchyNotIndexedException     if the hierarchy is not indexed in the currently used scopes
	 */
	@Nonnull
	private static EntitySchemaContract assertReferenceIsHierarchical(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Set<Scope> scopes
	) {
		Assert.isTrue(
			referenceSchema.isReferencedEntityTypeManaged(),
			() -> new EntityIsNotHierarchicalException(referenceSchema.getName(), referenceSchema.getReferencedEntityType())
		);

		final EntitySchemaContract referencedEntitySchema = filterByVisitor.getSchema(referenceSchema.getReferencedEntityType());
		Assert.isTrue(
			referencedEntitySchema.isWithHierarchy(),
			() -> new EntityIsNotHierarchicalException(referenceSchema.getName(), referenceSchema.getReferencedEntityType())
		);

		// verify the hierarchy is indexed in currently used scopes
		scopes.forEach(
			scope -> Assert.isTrue(
				referencedEntitySchema.isHierarchyIndexedInScope(scope),
				() -> new HierarchyNotIndexedException(referencedEntitySchema, scope)
			)
		);

		return referencedEntitySchema;
	}

	/**
	 * Creates a new array of {@link FilterConstraint} objects, excluding the elements at specified indexes.
	 * The method iterates through the input array and omits elements whose indexes are contained in the provided set.
	 *
	 * @param children        an array of {@link FilterConstraint} objects to process
	 * @param excludedIndexes a set of indexes that should be excluded from the resulting array
	 * @return a new array of {@link FilterConstraint} objects, excluding the elements at the specified indexes
	 */
	@Nonnull
	private static FilterBy createCopyExcludingIndexes(@Nonnull FilterConstraint[] children, @Nonnull IntSet excludedIndexes) {
		final FilterConstraint[] result = new FilterConstraint[children.length - excludedIndexes.size()];
		int resultIndex = 0;
		for (int i = 0; i < children.length; i++) {
			if (!excludedIndexes.contains(i)) {
				result[resultIndex++] = children[i];
			}
		}
		return filterBy(result);
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull FacetHaving facetHaving, @Nonnull FilterByVisitor filterByVisitor) {
		final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
		final EntitySchemaContract entitySchema = processingScope.getEntitySchemaOrThrowException();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(facetHaving.getReferenceName());
		final Set<Scope> scopes = processingScope.getScopes();
		isTrue(
			scopes.stream().anyMatch(referenceSchema::isFacetedInScope),
			() -> new ReferenceNotFacetedException(facetHaving.getReferenceName(), entitySchema)
		);

		final List<Formula> collectedFormulas = filterByVisitor.collectFromIndexes(
			entityIndex -> {
				final FacetFiltering facetFiltering = isolateFilteringConstraints(
					filterByVisitor, facetHaving.getChildren(), referenceSchema, scopes
				);

				final Formula mainFacetIds = filterByVisitor.getReferencedRecordIdFormula(
					entitySchema,
					referenceSchema,
					facetFiltering.mainFiltering()
				);

				final Formula finalFacetIds;
				if (facetFiltering.includeChildren()) {
					final HierarchyWithin hierarchyWithin = Objects.requireNonNull(facetFiltering.hierarchyFiltering())
						.apply(mainFacetIds.compute().getArray());
					finalFacetIds = FormulaFactory.or(
						Stream.concat(
							Stream.of(mainFacetIds),
							Arrays.stream(Objects.requireNonNull(facetFiltering.targetIndex()))
								.map(targetIndex ->
									HierarchyWithinTranslator.createFormulaFromHierarchyIndex(
										hierarchyWithin,
										Objects.requireNonNull(targetIndex),
										mainFacetIds.compute().getArray(),
										filterByVisitor.getQueryContext(),
										scopes,
										referenceSchema,
										Objects.requireNonNull(facetFiltering.targetSchema())
									)
								)
						).toArray(Formula[]::new)
					);
				} else {
					finalFacetIds = mainFacetIds;
				}

				// initialize the formula before compute is called
				finalFacetIds.initialize(filterByVisitor.getInternalExecutionContext());
				// first collect all formulas
				return entityIndex.getFacetReferencingEntityIdsFormula(
					facetHaving.getReferenceName(),
					(groupId, theFacetIds, recordIdBitmaps) -> {
						if (filterByVisitor.isFacetGroupConjunction(referenceSchema, groupId, WITH_DIFFERENT_FACETS_IN_GROUP)) {
							// AND relation is requested for facet of this group
							return new FacetGroupAndFormula(
								facetHaving.getReferenceName(), groupId, theFacetIds, recordIdBitmaps
							);
						} else {
							// default facet relation inside same group is or
							return new FacetGroupOrFormula(
								facetHaving.getReferenceName(), groupId, theFacetIds, recordIdBitmaps
							);
						}
					},
					finalFacetIds.compute()
				).stream();
			});

		// no single entity references this particular facet - return empty result quickly
		if (collectedFormulas.isEmpty()) {
			return EmptyFormula.INSTANCE;
		}

		// now aggregate formulas by group id - there will always be disjunction
		final Collection<Optional<FacetGroupFormula>> formulasGroupedByGroupId = collectedFormulas
			.stream()
			.map(FacetGroupFormula.class::cast)
			.collect(
				Collectors.groupingBy(
					it -> new GroupKey(it.getFacetGroupId()),
					Collectors.reducing(FacetGroupFormula::mergeWith)
				)
			).values();

		// now aggregate formulas by their group relation type
		final Map<Class<? extends FilterConstraint>, List<FacetGroupFormula>> formulasGroupedByAggregationType = formulasGroupedByGroupId
			.stream()
			.filter(Optional::isPresent)
			.map(Optional::get)
			.collect(
				Collectors.groupingBy(
					it -> {
						final Integer groupId = it.getFacetGroupId();
						if (groupId != null) {
							if (referenceSchema.isReferencedGroupTypeManaged()) {
								// OR relation is requested for facets of this group
								if (filterByVisitor.isFacetGroupDisjunction(referenceSchema, groupId, WITH_DIFFERENT_GROUPS)) {
									return Or.class;
									// NOT relation is requested for facets of this group
								} else if (filterByVisitor.isFacetGroupNegation(referenceSchema, groupId, WITH_DIFFERENT_GROUPS)) {
									return Not.class;
								}
							}
						}
						// default group relation is and
						return And.class;
					}
				)
			);

		// wrap formulas to appropriate containers
		final Formula notFormula = ofNullable(formulasGroupedByAggregationType.get(Not.class))
			.map(it -> FormulaFactory.or(it.toArray(Formula[]::new)))
			.orElse(null);
		final Formula andFormula = ofNullable(formulasGroupedByAggregationType.get(And.class))
			.map(it -> FormulaFactory.and(it.toArray(Formula[]::new)))
			.orElse(null);
		final Formula orFormula = ofNullable(formulasGroupedByAggregationType.get(Or.class))
			.map(it -> FormulaFactory.or(it.toArray(Formula[]::new)))
			.orElse(null);

		if (notFormula == null) {
			if (andFormula == null && orFormula == null) {
				throw new GenericEvitaInternalError("This should be not possible!");
			} else if (andFormula == null) {
				return orFormula;
			} else if (orFormula == null) {
				return andFormula;
			} else if (orFormula instanceof FacetGroupFormula) {
				return new CombinedFacetFormula(andFormula, orFormula);
			} else {
				return orFormula.getCloneWithInnerFormulas(
					ArrayUtils.insertRecordIntoArrayOnIndex(andFormula, orFormula.getInnerFormulas(), orFormula.getInnerFormulas().length)
				);
			}
		} else {
			if (andFormula == null && orFormula == null) {
				return new FutureNotFormula(notFormula);
			} else if (andFormula == null) {
				return new NotFormula(notFormula, orFormula);
			} else if (orFormula == null) {
				return new NotFormula(notFormula, andFormula);
			} else {
				return new NotFormula(
					notFormula,
					new CombinedFacetFormula(andFormula, orFormula)
				);
			}
		}
	}

	/**
	 * Represents a unique key for grouping entities or facets based on their group ID.
	 * This class is a record that holds an optional group ID and provides custom implementations
	 * for equality and hash code computation.
	 *
	 * The GroupKey is typically used as an identifier for aggregating or organizing data
	 * in the context of group-based operations.
	 *
	 * @param groupId the ID of the group, which may be null
	 */
	public record GroupKey(@Nullable Integer groupId) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			GroupKey groupKey = (GroupKey) o;
			return Objects.equals(this.groupId, groupKey.groupId);
		}

		@Override
		public int hashCode() {
			return this.groupId == null ? 0 : this.groupId.hashCode();
		}

	}

	/**
	 * Facet filtering constraints bundle.
	 *
	 * @param mainFiltering      constraint to be used for general facet filtering
	 * @param hierarchyFiltering constraint to be used to match additional children of all matched hierarchy facets
	 * @param targetSchema       the schema of the target entity
	 * @param targetIndex        the global index of the target entity
	 */
	public record FacetFiltering(
		@Nonnull FilterBy mainFiltering,
		@Nullable Function<int[], HierarchyWithin> hierarchyFiltering,
		@Nullable EntitySchemaContract targetSchema,
		@Nullable EntityIndex[] targetIndex
	) {

		public FacetFiltering {
			Assert.isPremiseValid(
				hierarchyFiltering == null || (targetSchema != null && targetIndex != null),
				() -> "Hierarchy filtering must be accompanied by target schema and index!"
			);
		}

		/**
		 * Returns true if the hierarchical facets should include all or certain children.
		 *
		 * @return true if the hierarchical facets should include all or certain children
		 */
		public boolean includeChildren() {
			return this.hierarchyFiltering != null;
		}
	}

}
