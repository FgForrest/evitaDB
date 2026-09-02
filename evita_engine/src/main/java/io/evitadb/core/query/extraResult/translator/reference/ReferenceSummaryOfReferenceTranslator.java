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

package io.evitadb.core.query.extraResult.translator.reference;

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.filter.SeparateEntityScopeContainer;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.query.require.ReferenceSummaryOfReference;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
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
import io.evitadb.core.query.extraResult.translator.reference.producer.FilteringFormulaPredicate;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryAdapter;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryResultAdapter;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.collection.BitmapIntoBitmapCollector;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.utils.Assert;
import io.evitadb.utils.Functions;

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
 * Translates the per-reference form of the reference-summary require constraint
 * ({@link ReferenceSummaryOfReference}) into a shared {@link ReferenceSummaryProducer} instance. Because the
 * constraint can carry nested {@link io.evitadb.api.query.require.ReferenceHistogramStatistics} children, this
 * translator implements {@link SelfTraversingTranslator}: the visitor skips automatic child traversal and this
 * class is responsible for dispatching histogram children manually after the producer is pre-registered.
 *
 * The manual dispatch strategy in {@link #createProducer} is:
 *
 * 1. Resolve / create the {@link ReferenceSummaryProducer} via the shared `createProducerInternal` helper.
 * 2. Pre-register the producer via {@link ExtraResultPlanningVisitor#registerProducer} — `registerProducer` is
 *    idempotent, so the planner's own post-return registration becomes a no-op. This must happen before children
 *    are dispatched so that {@link ReferenceHistogramStatisticsTranslator} can look the producer up via
 *    `findExistingProducer`.
 * 3. Each {@link io.evitadb.api.query.require.ReferenceHistogramStatistics} child is dispatched inside a
 *    reference-scoped context (created via `executeInContext`) that exposes the concrete reference schema. The
 *    child translator validates the histogram index name strictly — it throws if the name is not defined on the
 *    target reference in every active scope (strict, not lenient).
 *
 * All planning operations are cheap; the heavy lifting is deferred to
 * {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceSummaryOfReferenceTranslator
	implements RequireConstraintTranslator<ReferenceSummaryOfReference>, SelfTraversingTranslator {

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
			() -> "Reference summary of `" + referenceSchema.getName() + "` group filter: " + filterGroupBy
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
			return Functions.intAlwaysFalse();
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
		final Supplier<String> descriptionSupplier = () ->
			"Facet summary `" + referenceSchema.getName() + "` facet ordering: " + orderBy;
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

		final Supplier<String> descriptionSupplier = () ->
			"Facet summary `" + referenceSchema.getName() + "` group ordering: " + orderBy;
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
	 * Finds an existing {@link ReferenceSummaryProducer} that matches the given adapter, or creates a new one.
	 * This method encapsulates the common logic shared by both the default (all-references) and
	 * per-reference producer creation paths: computing the requested facets from user filtering formulas,
	 * looking up an existing producer by adapter class, and constructing a new one if none is found.
	 *
	 * @param facetIndexes       the pre-computed facet reference indexes to use for the producer
	 * @param resultAdapter      adapter that decides which concrete DTO the producer emits
	 * @param extraResultPlanner the extra result planning visitor
	 * @return the found or newly created producer
	 */
	@Nonnull
	static ReferenceSummaryProducer findOrCreateProducer(
		@Nonnull List<Map<String, FacetReferenceIndex>> facetIndexes,
		@Nonnull ReferenceSummaryResultAdapter<? extends ReferenceGroupStatistics> resultAdapter,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
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

		// find existing ReferenceSummaryProducer for potential reuse — only match one wired with the same adapter
		// class, so a deprecated-form producer and a new-form producer can coexist independently in mixed queries
		ReferenceSummaryProducer referenceSummaryProducer = extraResultPlanner.findExistingProducer(
			ReferenceSummaryProducer.class,
			existing -> existing.getResultAdapter().getClass() == resultAdapter.getClass()
		);
		if (referenceSummaryProducer == null) {
			// now create the producer instance that has all pointers necessary to compute result
			// all operations above should be relatively cheap comparing to final result computation, that is deferred
			// to ReferenceSummaryProducer#fabricate method
			referenceSummaryProducer = new ReferenceSummaryProducer(
				extraResultPlanner.getFilteringFormula(),
				extraResultPlanner.getFilteringFormulaWithoutUserFilter(),
				facetIndexes,
				requestedFacets,
				resultAdapter
			);
		}
		return referenceSummaryProducer;
	}

	/**
	 * Shared logic for creating a {@link ReferenceSummaryProducer} for a specific reference from the common set
	 * of parameters extracted from either {@link ReferenceSummaryOfReference} or
	 * {@link io.evitadb.api.query.require.FacetSummaryOfReference}.
	 *
	 * @param referenceName          the name of the reference
	 * @param statisticsDepth        the depth of statistics to compute
	 * @param referenceEntityRequirement optional entity fetch requirement for reference entities
	 * @param groupEntityRequirement optional entity group fetch requirement for group entities
	 * @param filterBy               optional filter for individual references
	 * @param filterGroupBy          optional filter for reference groups
	 * @param orderBy                optional ordering for individual references
	 * @param orderGroupBy           optional ordering for reference groups
	 * @param resultAdapter          adapter that decides which concrete DTO the producer emits — the deprecated
	 *                               {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} or the
	 *                               canonical {@link io.evitadb.api.requestResponse.extraResult.ReferenceSummary}.
	 *                               Reuse of an existing producer instance is keyed on the adapter's runtime class
	 *                               so that a mixed request (both deprecated and new constraints) results in two
	 *                               independent producer instances, one per adapter.
	 * @param extraResultPlanner     the extra result planning visitor
	 * @return the created producer, or null
	 */
	@Nonnull
	public static ExtraResultProducer createProducerInternal(
		@Nonnull String referenceName,
		@Nonnull FacetStatisticsDepth statisticsDepth,
		@Nullable EntityFetch referenceEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy filterGroupBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy orderGroupBy,
		@Nonnull ReferenceSummaryResultAdapter<? extends ReferenceGroupStatistics> resultAdapter,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		final EntitySchemaContract entitySchema = extraResultPlanner.getSchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));

		final ProcessingScope processingScope = extraResultPlanner.getProcessingScope();
		final Set<Scope> scopes = processingScope.getScopes();

		isTrue(
			scopes.stream().allMatch(referenceSchema::isFacetedInScope),
			() -> new ReferenceNotFacetedException(referenceName, entitySchema)
		);
		// the *OfReference forms route through here rather than through the sibling translator's own
		// `createProducerInternal`, so the dependency has to be recorded in both. Past the assertion, which has just
		// established the flag is on in every requested scope
		extraResultPlanner.getQueryContext().recordRequestedReferenceCapability(
			entitySchema, referenceName, Capability.FACETED, scopes
		);

		// collect all facet statistics — filter by scope so that only live/archived indexes
		// requested by the enclosing `require(inScope(...))` participate, matching the behaviour of
		// the sibling ReferenceSummaryTranslator
		final TargetIndexes<?> indexSetToUse = extraResultPlanner.getIndexSetToUse();
		final List<Map<String, FacetReferenceIndex>> facetIndexes = indexSetToUse.getIndexStream(EntityIndex.class)
			.filter(index -> scopes.contains(index.getIndexKey().scope()))
			.map(EntityIndex::getFacetingEntities)
			.collect(Collectors.toList());

		final ReferenceSummaryProducer referenceSummaryProducer = findOrCreateProducer(
			facetIndexes, resultAdapter, extraResultPlanner
		);

		final EntityFetch facetEntityRequirement = referenceEntityRequirement != null ?
			verifyFetch(referenceSchema.getReferencedEntityType(), referenceEntityRequirement, extraResultPlanner) :
			null;
		final EntityGroupFetch groupEntityReq;
		if (groupEntityRequirement != null) {
			final String referencedGroupType = referenceSchema.getReferencedGroupType();
			groupEntityReq = referencedGroupType != null ?
				verifyFetch(referencedGroupType, groupEntityRequirement, extraResultPlanner) :
				groupEntityRequirement;
		} else {
			groupEntityReq = null;
		}
		final IntPredicate facetPredicate = filterBy != null
			? createFacetPredicate(extraResultPlanner, filterBy, referenceSchema, true)
			: null;
		final IntPredicate groupPredicate = filterGroupBy != null
			? createFacetGroupPredicate(extraResultPlanner, filterGroupBy, referenceSchema, true)
			: null;
		final NestedContextSorter facetSorter = orderBy != null
			? createFacetSorter(
				extraResultPlanner, orderBy, findLocale(filterBy), extraResultPlanner, referenceSchema, true
			)
			: null;
		final NestedContextSorter groupSorter = orderGroupBy != null
			? createFacetGroupSorter(
				extraResultPlanner, orderGroupBy, findLocale(filterGroupBy), extraResultPlanner, referenceSchema, true
			)
			: null;
		referenceSummaryProducer.requireReferenceReferenceSummary(
			referenceSchema,
			statisticsDepth,
			facetPredicate,
			groupPredicate,
			facetSorter,
			groupSorter,
			facetEntityRequirement,
			groupEntityReq
		);
		return referenceSummaryProducer;
	}

	/**
	 * Creates or retrieves the shared {@link ReferenceSummaryProducer} for the named reference, then pre-registers
	 * it and manually dispatches any nested {@link io.evitadb.api.query.require.ReferenceHistogramStatistics}
	 * children.
	 *
	 * The pre-registration dance is necessary because this class is a {@link SelfTraversingTranslator}: the
	 * visitor does not automatically walk children. Pre-registering before child dispatch ensures that
	 * {@link ReferenceHistogramStatisticsTranslator} can locate the producer via `findExistingProducer` even
	 * though the planner has not yet received the producer back from this method's return.
	 */
	@Nullable
	@Override
	public ExtraResultProducer createProducer(
		@Nonnull ReferenceSummaryOfReference referenceSummaryOfReference,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		final ExtraResultProducer producer = createProducerInternal(
			referenceSummaryOfReference.getReferenceName(),
			referenceSummaryOfReference.getStatisticsDepth(),
			referenceSummaryOfReference.getReferenceEntityRequirement().orElse(null),
			referenceSummaryOfReference.getGroupEntityRequirement().orElse(null),
			referenceSummaryOfReference.getFilterBy().orElse(null),
			referenceSummaryOfReference.getFilterGroupBy().orElse(null),
			referenceSummaryOfReference.getOrderBy().orElse(null),
			referenceSummaryOfReference.getOrderGroupBy().orElse(null),
			ReferenceSummaryAdapter.INSTANCE,
			extraResultPlanner
		);
		// pre-register the producer so nested histogram translators can look it up via
		// findExistingProducer — registerProducer is idempotent, so the planner's own
		// post-return registration via ExtraResultPlanningVisitor.visit becomes a no-op
		extraResultPlanner.registerProducer(producer);
		// dispatch nested histogramStatistics children to their translator — this container is
		// a SelfTraversingTranslator, so children are not walked automatically
		final ReferenceSchemaContract referenceSchema = extraResultPlanner.getSchema()
			.getReference(referenceSummaryOfReference.getReferenceName())
			.orElseThrow(() -> new ReferenceNotFoundException(
				referenceSummaryOfReference.getReferenceName(), extraResultPlanner.getSchema()
			));
		for (final RequireConstraint child : referenceSummaryOfReference.getChildren()) {
			if (child instanceof ReferenceHistogramStatistics) {
				extraResultPlanner.executeInContext(
					child,
					() -> referenceSchema,
					() -> null,
					() -> {
						child.accept(extraResultPlanner);
						return null;
					}
				);
			}
		}
		return producer;
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

}
