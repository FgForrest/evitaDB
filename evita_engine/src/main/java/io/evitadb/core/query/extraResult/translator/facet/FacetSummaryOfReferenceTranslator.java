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

package io.evitadb.core.query.extraResult.translator.facet;

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.filter.SeparateEntityScopeContainer;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetSummaryOfReference;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.ReferenceNotFacetedException;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.facet.producer.FacetSummaryProducer;
import io.evitadb.core.query.extraResult.translator.facet.producer.FilteringFormulaPredicate;
import io.evitadb.core.query.extraResult.translator.reference.EntityFetchTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.collection.BitmapIntoBitmapCollector;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link FacetSummaryOfReference} to {@link FacetSummaryProducer}.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetSummaryOfReferenceTranslator implements RequireConstraintTranslator<FacetSummaryOfReference>, SelfTraversingTranslator {

	/**
	 * Creates a predicate for filtering facet groups.
	 *
	 * @param extraResultPlanningVisitor the extra result planning visitor with context
	 * @param filterGroupBy              the filter group to apply
	 * @param referenceSchema            the reference schema contract
	 * @param required                   indicates if the facet groups are required
	 * @return the predicate for filtering facet groups, or null if not required
	 */
	@Nullable
	static IntPredicate createFacetGroupPredicate(
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor,
		@Nonnull FilterGroupBy filterGroupBy,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean required
	) {
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		if (required) {
			Assert.isTrue(
				referencedGroupType != null,
				() -> "Facet groups of reference `" + referenceSchema.getName() + "` cannot be filtered because they relate to " +
					"non-grouped entity type `" + referenceSchema.getReferencedEntityType() + "`."
			);
			Assert.isTrue(
				referenceSchema.isReferencedGroupTypeManaged(),
				() -> "Facet groups of reference `" + referenceSchema.getName() + "` cannot be filtered because they relate to " +
					"non-managed entity type `" + referencedGroupType + "`."
			);
		} else if (referencedGroupType == null || !referenceSchema.isReferencedGroupTypeManaged()) {
			return null;
		}
		final QueryPlanningContext queryContext = extraResultPlanningVisitor.getQueryContext();
		return new FilteringFormulaPredicate(
			queryContext,
			extraResultPlanningVisitor.getProcessingScope().getScopes(),
			new FilterBy(filterGroupBy.getChildren()),
			referencedGroupType,
			() -> "Facet summary of `" + referenceSchema.getName() + "` group filter: " + filterGroupBy
		);
	}

	/**
	 * Creates a predicate for filtering facets.
	 *
	 * @param extraResultPlanningVisitor the extra result planning visitor with context
	 * @param filterBy                   The filter criteria.
	 * @param referenceSchema            The schema of the referenced entity.
	 * @param required                   Indicates if the facet is required.
	 * @return The created facet predicate.
	 */
	@Nonnull
	static IntPredicate createFacetPredicate(
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor,
		@Nonnull FilterBy filterBy,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean required
	) {
		if (required) {
			Assert.isTrue(
				referenceSchema.isReferencedEntityTypeManaged(),
				() -> "Facets of reference `" + referenceSchema.getName() + "` cannot be filtered because they relate to " +
					"non-managed entity type `" + referenceSchema.getReferencedEntityType() + "`."
			);
		} else if (!referenceSchema.isReferencedEntityTypeManaged()) {
			return pk -> false;
		}

		final QueryPlanningContext queryContext = extraResultPlanningVisitor.getQueryContext();
		return new FilteringFormulaPredicate(
			queryContext,
			extraResultPlanningVisitor.getProcessingScope().getScopes(),
			filterBy,
			referenceSchema.getReferencedEntityType(),
			() -> "Facet summary of `" + referenceSchema.getName() + "` facet filter: " + filterBy
		);
	}

	/**
	 * Creates a facet sorter based on the provided parameters.
	 *
	 * @param extraResultPlanningVisitor the extra result planning visitor with context
	 * @param orderBy                    the ordering criteria for the facet
	 * @param locale                     the locale used for sorting
	 * @param extraResultPlanner         the extra result planning visitor
	 * @param referenceSchema            the reference schema contract
	 * @param required                   indicates whether sorting is required or optional
	 * @return the created facet sorter, or null if the reference schema is not managed and sorting is not required
	 */
	@Nullable
	static NestedContextSorter createFacetSorter(
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor,
		@Nonnull OrderBy orderBy,
		@Nullable Locale locale,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean required
	) {
		if (required) {
			Assert.isTrue(
				referenceSchema.isReferencedEntityTypeManaged(),
				() -> "Facets of reference `" + referenceSchema.getName() + "` cannot be sorted because they relate to " +
					"non-managed entity type `" + referenceSchema.getReferencedEntityType() + "`."
			);
		} else if (!referenceSchema.isReferencedEntityTypeManaged()) {
			return null;
		}
		final Supplier<String> descriptionSupplier = () -> "Facet summary `" + referenceSchema.getName() + "` facet ordering: " + orderBy;
		return extraResultPlanner.getEntityCollection(referenceSchema.getReferencedEntityType())
			.map(collection -> extraResultPlanner.createSorter(orderBy, locale, collection, descriptionSupplier))
			.orElseGet(() -> new NestedContextSorter(extraResultPlanningVisitor.createExecutionContext(), descriptionSupplier));
	}

	/**
	 * Creates a sorter for facet group ordering.
	 *
	 * @param extraResultPlanningVisitor the extra result planning visitor with context
	 * @param orderBy                    The order by criteria for the facet groups.
	 * @param locale                     The locale used for sorting.
	 * @param extraResultPlanner         The extra result planner used for sorting.
	 * @param referenceSchema            The reference schema for the facet groups.
	 * @param required                   Indicates if sorting is required.
	 * @return The created sorter for facet group ordering, or null if not required.
	 */
	@Nullable
	static NestedContextSorter createFacetGroupSorter(
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor,
		@Nonnull OrderGroupBy orderBy,
		@Nullable Locale locale,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean required
	) {
		if (required) {
			Assert.isTrue(
				referenceSchema.getReferencedGroupType() != null,
				() -> "Facet groups of reference `" + referenceSchema.getName() + "` cannot be sorted because they relate to " +
					"non-grouped entity type `" + referenceSchema.getReferencedEntityType() + "`."
			);
			Assert.isTrue(
				referenceSchema.isReferencedGroupTypeManaged(),
				() -> "Facet groups of reference `" + referenceSchema.getName() + "` cannot be sorted because they relate to " +
					"non-managed entity type `" + referenceSchema.getReferencedGroupType() + "`."
			);
		} else if (referenceSchema.getReferencedGroupType() == null || !referenceSchema.isReferencedGroupTypeManaged()) {
			return null;
		}

		final Supplier<String> descriptionSupplier = () -> "Facet summary `" + referenceSchema.getName() + "` group ordering: " + orderBy;
		return extraResultPlanner.getEntityCollection(referenceSchema.getReferencedGroupType())
			.map(collection -> extraResultPlanner.createSorter(orderBy, locale, collection, descriptionSupplier))
			.orElseGet(() -> new NestedContextSorter(extraResultPlanningVisitor.createExecutionContext(), descriptionSupplier));
	}

	/**
	 * Finds the Locale based on the given filter constraint.
	 *
	 * @param filterBy the filter constraint to search for Locale
	 * @return the Locale found or null if not found
	 */
	@Nullable
	static Locale findLocale(@Nullable GenericConstraint<FilterConstraint> filterBy) {
		return filterBy == null ?
			null :
			ofNullable(
				FinderVisitor.findConstraint(
					filterBy,
					EntityLocaleEquals.class::isInstance,
					SeparateEntityScopeContainer.class::isInstance
				)
			)
				.map(it -> ((EntityLocaleEquals) it).getLocale())
				.orElse(null);
	}

	/**
	 * Verify the fetch requirement for a given referenced type.
	 *
	 * @param referencedType     the type to be referenced
	 * @param requirement        the fetch requirement to be verified
	 * @param extraResultPlanner the visitor used for extra result planning
	 * @param <T>                the type of the fetch requirement
	 * @return the verified fetch requirement
	 */
	@Nonnull
	private static <T extends EntityFetchRequire> T verifyFetch(
		@Nonnull String referencedType,
		@Nonnull T requirement,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		final EntitySchemaContract referencedSchema = extraResultPlanner.getSchema(referencedType);
		EntityFetchTranslator.verifyEntityFetchLocalizedAttributes(referencedSchema, requirement, extraResultPlanner);
		return requirement;
	}

	@Nullable
	@Override
	public ExtraResultProducer createProducer(
		@Nonnull FacetSummaryOfReference facetSummaryOfReference,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		final String referenceName = facetSummaryOfReference.getReferenceName();
		final EntitySchemaContract entitySchema = extraResultPlanner.getSchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));

		final ProcessingScope processingScope = extraResultPlanner.getProcessingScope();
		final Set<Scope> scopes = processingScope.getScopes();

		isTrue(
			scopes.stream().allMatch(referenceSchema::isFacetedInScope),
			() -> new ReferenceNotFacetedException(referenceName, entitySchema)
		);

		// find user filters that enclose variable user defined part
		final Set<Formula> formulaScope = extraResultPlanner.getUserFilteringFormula().isEmpty() ?
			Set.of(extraResultPlanner.getFilteringFormula()) :
			extraResultPlanner.getUserFilteringFormula();
		// find all requested facets
		final Map<String, Bitmap> requestedFacets = formulaScope
			.stream()
			.flatMap(it -> FormulaFinder.find(it, FacetGroupFormula.class, LookUp.SHALLOW).stream())
			.collect(
				Collectors.groupingBy(
					FacetGroupFormula::getReferenceName,
					Collectors.mapping(
						FacetGroupFormula::getFacetIds,
						BitmapIntoBitmapCollector.INSTANCE
					)
				)
			);
		// collect all facet statistics
		final TargetIndexes<?> indexSetToUse = extraResultPlanner.getIndexSetToUse();
		final List<Map<String, FacetReferenceIndex>> facetIndexes = indexSetToUse.getIndexStream(EntityIndex.class)
			.map(EntityIndex::getFacetingEntities)
			.collect(Collectors.toList());

		// find existing FacetSummaryProducer for potential reuse
		FacetSummaryProducer facetSummaryProducer = extraResultPlanner.findExistingProducer(FacetSummaryProducer.class);
		if (facetSummaryProducer == null) {
			// now create the producer instance that has all pointer necessary to compute result
			// all operations above should be relatively cheap comparing to final result computation, that is deferred
			// to FacetSummaryProducer#fabricate method
			facetSummaryProducer = new FacetSummaryProducer(
				extraResultPlanner.getFilteringFormula(),
				extraResultPlanner.getFilteringFormulaWithoutUserFilter(),
				facetIndexes,
				requestedFacets
			);
		}

		final EntityFetch facetEntityRequirement = facetSummaryOfReference.getFacetEntityRequirement()
			.map(it -> verifyFetch(referenceSchema.getReferencedEntityType(), it, extraResultPlanner))
			.orElse(null);
		final EntityGroupFetch groupEntityRequirement = facetSummaryOfReference.getGroupEntityRequirement()
			.map(
				it -> ofNullable(referenceSchema.getReferencedGroupType())
					.map(group -> verifyFetch(group, it, extraResultPlanner))
					.orElse(it)
			)
			.orElse(null);
		facetSummaryProducer.requireReferenceFacetSummary(
			referenceSchema,
			facetSummaryOfReference.getStatisticsDepth(),
			facetSummaryOfReference.getFilterBy().map(it -> createFacetPredicate(extraResultPlanner, it, referenceSchema, true)).orElse(null),
			facetSummaryOfReference.getFilterGroupBy().map(it -> createFacetGroupPredicate(extraResultPlanner, it, referenceSchema, true)).orElse(null),
			facetSummaryOfReference.getOrderBy().map(it -> createFacetSorter(extraResultPlanner, it, findLocale(facetSummaryOfReference.getFilterBy().orElse(null)), extraResultPlanner, referenceSchema, true)).orElse(null),
			facetSummaryOfReference.getOrderGroupBy().map(it -> createFacetGroupSorter(extraResultPlanner, it, findLocale(facetSummaryOfReference.getFilterGroupBy().orElse(null)), extraResultPlanner, referenceSchema, true)).orElse(null),
			facetEntityRequirement,
			groupEntityRequirement
		);
		return facetSummaryProducer;
	}

}
