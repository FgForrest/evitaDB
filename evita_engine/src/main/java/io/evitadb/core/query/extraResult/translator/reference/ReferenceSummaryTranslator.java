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

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryAdapter;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.SummaryDeclaration;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryResultAdapter;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import static io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator.createFacetGroupPredicate;
import static io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator.createFacetGroupSorter;
import static io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator.createFacetPredicate;
import static io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator.createFacetSorter;
import static io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator.findLocale;
import static io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator.findOrCreateProducer;

/**
 * Translates the all-references form of the reference-summary require constraint
 * ({@link ReferenceSummary}) into a shared {@link ReferenceSummaryProducer} instance. Because the constraint
 * can carry nested {@link io.evitadb.api.query.require.ReferenceHistogramStatistics} children, this translator
 * implements {@link SelfTraversingTranslator}: the visitor skips automatic child traversal and this class is
 * responsible for dispatching histogram children manually after the producer is pre-registered.
 *
 * The manual dispatch strategy is:
 *
 * 1. {@link #createProducer} resolves / creates the {@link ReferenceSummaryProducer} and immediately pre-registers
 *    it via {@link ExtraResultPlanningVisitor#registerProducer} so that child translators can look it up.
 * 2. Each {@link io.evitadb.api.query.require.ReferenceHistogramStatistics} child is dispatched via
 *    {@link #dispatchHistogramToMatchingReferences}: the all-references form is **applies-where-defined**
 *    — references that don't declare a requested histogram name in every active scope are silently skipped,
 *    and the histogram constraint is narrowed per reference to the names that reference actually declares.
 *    A typo guard fires when a requested histogram name is not declared on **any** reference in the schema
 *    (in every active scope) — that case throws {@link io.evitadb.exception.EvitaInvalidUsageException} so
 *    user typos surface rather than silently producing no histograms. Users who want strict per-reference
 *    validation must switch to {@link ReferenceSummaryOfReferenceTranslator} (per-reference form, where
 *    every requested name must exist on the named reference in every active scope), and users who want the
 *    computation limited to a subset of scopes must wrap the histogram requirement in
 *    {@link io.evitadb.api.query.require.RequireInScope}.
 *
 * All planning operations are cheap; the heavy lifting is deferred to
 * {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceSummaryTranslator
	implements RequireConstraintTranslator<ReferenceSummary>, SelfTraversingTranslator {

	/**
	 * Shared logic for creating a {@link ReferenceSummaryProducer} from the common set of parameters extracted
	 * from either {@link ReferenceSummary} or {@link FacetSummary}.
	 *
	 * @param statisticsDepth            the depth of statistics to compute
	 * @param referenceEntityRequirement optional entity fetch requirement for reference entities
	 * @param groupEntityRequirement     optional entity group fetch requirement for group entities
	 * @param filterBy                   optional filter for individual references
	 * @param filterGroupBy              optional filter for reference groups
	 * @param orderBy                    optional ordering for individual references
	 * @param orderGroupBy               optional ordering for reference groups
	 * @param referencesWithHistograms   names of the references the summary computes requested histograms for - see
	 *                                   {@link #collectReferencesWithRequestedHistograms}; empty when it requests none
	 * @param resultAdapter              adapter that decides which concrete DTO the producer emits — the deprecated
	 *                                   {@link FacetSummary} or the
	 *                                   canonical {@link ReferenceSummary}.
	 *                                   Reuse of an existing producer instance is keyed on the adapter's runtime class
	 *                                   so that a mixed request (both deprecated and new constraints) results in two
	 *                                   independent producer instances, one per adapter.
	 * @param extraResultPlanner         the extra result planning visitor
	 * @return the created producer, or null
	 */
	@Nonnull
	public static ExtraResultProducer createProducerInternal(
		@Nonnull FacetStatisticsDepth statisticsDepth,
		@Nullable EntityFetch referenceEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy filterGroupBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy orderGroupBy,
		@Nonnull Set<String> referencesWithHistograms,
		@Nonnull ReferenceSummaryResultAdapter<? extends ReferenceGroupStatistics> resultAdapter,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		final ProcessingScope processingScope = extraResultPlanner.getProcessingScope();
		final Set<Scope> scopes = processingScope.getScopes();
		final EntitySchemaContract entitySchema = processingScope.getEntitySchema()
			.orElseGet(extraResultPlanner::getSchema);

		// The all-references summary forms - `facetSummary` and `referenceSummary` - both route through this method,
		// which is why the dependency on `faceted()` is recorded here rather than in each translator. The
		// *OfReference variants route through the sibling translator's own `createProducerInternal` and record the
		// dependency there instead. Summarising is by far the commoner use of the flag; recorded per scope rather
		// than for the whole set, because a reference faceted in one scope only is depended upon in that scope only.
		for (final ReferenceSchemaContract referenceSchema : entitySchema.getReferences().values()) {
			// Asking a reflected reference for an *inherited* `faceted()` before its target is attached throws
			// instead of answering, and that is a legal schema state rather than an error - so it is stepped over
			// rather than allowed to fail the query. Narrowed to the inherited case on purpose: a reflected
			// reference stating its own `faceted()` answers fine while detached, and skipping it would leave a live
			// capability unrecorded.
			if (referenceSchema instanceof ReflectedReferenceSchemaContract reflectedReference
				&& reflectedReference.isFacetedInherited()
				&& !reflectedReference.isReflectedReferenceAvailable()
			) {
				continue;
			}
			for (final Scope scope : scopes) {
				if (referenceSchema.isFacetedInScope(scope)) {
					extraResultPlanner.getQueryContext().recordRequestedCapability(
						entitySchema, null, ElementKind.REFERENCE, referenceSchema.getName(),
						Capability.FACETED, scope
					);
				}
			}
		}

		// collect all facet statistics
		final TargetIndexes<?> indexSetToUse = extraResultPlanner.getIndexSetToUse();
		final List<Map<String, FacetReferenceIndex>> facetIndexes = indexSetToUse.getIndexStream(EntityIndex.class)
			.filter(index -> scopes.contains(index.getIndexKey().scope()))
			.map(EntityIndex::getFacetingEntities)
			.collect(Collectors.toList());

		final ReferenceSummaryProducer referenceSummaryProducer = findOrCreateProducer(
			facetIndexes, resultAdapter, extraResultPlanner
		);

		// the references a reference-specific summary of the same spelling claims are not described by this
		// constraint, so they take no part in validating its requirements - see `collectReferenceSpecificNames`
		final Set<String> referencesDescribedBySpecificSummary = resultAdapter.collectReferenceSpecificNames(
			extraResultPlanner.getEvitaRequest().getQuery().getRequire()
		);

		final EntityFetch facetEntityRequirement = referenceEntityRequirement != null ?
			verifyFetch(
				entitySchema,
				referenceSchema -> referenceSchema.isReferencedEntityTypeManaged() ?
					referenceSchema.getReferencedEntityType() : null,
				referenceEntityRequirement,
				referencesDescribedBySpecificSummary,
				extraResultPlanner
			) :
			null;
		final EntityGroupFetch groupEntityReq = groupEntityRequirement != null ?
			verifyFetch(
				entitySchema,
				referenceSchema -> referenceSchema.isReferencedGroupTypeManaged()
				                   ? referenceSchema.getReferencedGroupType()
					: null,
				groupEntityRequirement,
				referencesDescribedBySpecificSummary,
				extraResultPlanner
			) :
			null;

		// a second all-references summary constraint of the same kind must not silently replace the first one
		referenceSummaryProducer.assertDefaultSummaryNotRedeclared(
			new SummaryDeclaration(
				statisticsDepth, referenceEntityRequirement, groupEntityRequirement,
				filterBy, filterGroupBy, orderBy, orderGroupBy
			)
		);

		// the predicates and the sorters are created while the query is planned, for every reference the summary covers,
		// so that their filters and orderings are checked and counted with the query whether the reference has an option
		// to filter and order or not
		final Function<ReferenceSchemaContract, IntPredicate> facetPredicate = filterBy == null ?
			null :
			createForSummarizedReferences(
				entitySchema, scopes, referencesDescribedBySpecificSummary, referencesWithHistograms,
				referenceSchema -> createFacetPredicate(extraResultPlanner, filterBy, referenceSchema, false)
			);
		final Function<ReferenceSchemaContract, IntPredicate> groupPredicate = filterGroupBy == null ?
			null :
			createForSummarizedReferences(
				entitySchema, scopes, referencesDescribedBySpecificSummary, referencesWithHistograms,
				referenceSchema -> createFacetGroupPredicate(extraResultPlanner, filterGroupBy, referenceSchema, false)
			);
		final Function<ReferenceSchemaContract, NestedContextSorter> facetSorter = orderBy == null ?
			null :
			createForSummarizedReferences(
				entitySchema, scopes, referencesDescribedBySpecificSummary, referencesWithHistograms,
				referenceSchema -> createFacetSorter(
					extraResultPlanner, orderBy, findLocale(filterBy), extraResultPlanner, referenceSchema, false
				)
			);
		final Function<ReferenceSchemaContract, NestedContextSorter> groupSorter = orderGroupBy == null ?
			null :
			createForSummarizedReferences(
				entitySchema, scopes, referencesDescribedBySpecificSummary, referencesWithHistograms,
				referenceSchema -> createFacetGroupSorter(
					extraResultPlanner, orderGroupBy, findLocale(filterGroupBy), extraResultPlanner, referenceSchema,
					false
				)
			);
		referenceSummaryProducer.requireDefaultReferenceSummary(
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
	 * Creates the predicate or the sorter of every reference the summary of all references covers - each reference
	 * faceted in one of the processing scopes or carrying a histogram the summary requests, that no reference-specific
	 * summary claims, a reflection not attached yet excepted - right away, and returns the function the producer
	 * resolves the predicate or the sorter of a reference by when the summary is computed. A reference carrying a
	 * requested histogram is covered whether it is faceted or not: the summary computes the histogram for its groups,
	 * and resolves the predicates and the sorters of the reference to do so.
	 *
	 * The filter and the ordering of the summary are evaluated against the entity type each reference targets, and they
	 * are a part of the query whether that reference holds an option to filter and order or not: creating the
	 * predicates and the sorters while the query is planned checks the filter and the ordering against every such
	 * type - so the query fails or passes regardless of the data - and hands the schema capabilities they request to the
	 * planning context of the query, which counts them once with its own before the plan is executed. A predicate or a
	 * sorter created only when the summary is computed would do neither: it would be created for the references holding
	 * an option only, and after the query had already counted what it requested.
	 *
	 * The function falls back to creating the predicate or the sorter of a reference outside that set, should the
	 * summary ever reach one.
	 *
	 * @param entitySchema                         the schema of the summarized entity type
	 * @param scopes                               the processing scopes of the summary
	 * @param referencesDescribedBySpecificSummary the references a reference-specific summary claims
	 * @param referencesWithHistograms             the references the summary computes requested histograms for
	 * @param factory                              creates the predicate or the sorter of a reference, NULL when it has
	 *                                             none
	 * @param <T>                                  the type of what is created - a predicate or a sorter
	 * @return the function resolving what was created for a reference, NULL when the reference has none
	 */
	@Nonnull
	private static <T> Function<ReferenceSchemaContract, T> createForSummarizedReferences(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Set<Scope> scopes,
		@Nonnull Set<String> referencesDescribedBySpecificSummary,
		@Nonnull Set<String> referencesWithHistograms,
		@Nonnull Function<ReferenceSchemaContract, T> factory
	) {
		final Map<String, T> created = CollectionUtils.createHashMap(entitySchema.getReferences().size());
		for (final ReferenceSchemaContract referenceSchema : entitySchema.getReferences().values()) {
			// a reflection not attached yet holds no option, and it cannot name the group type its groups would be
			// filtered or ordered by - even when it states its own `faceted()` - see `isUnattachedReflection`
			if (referencesDescribedBySpecificSummary.contains(referenceSchema.getName())
				|| isUnattachedReflection(referenceSchema)
				|| (!referencesWithHistograms.contains(referenceSchema.getName())
				&& scopes.stream().noneMatch(referenceSchema::isFacetedInScope))
			) {
				continue;
			}
			created.put(referenceSchema.getName(), factory.apply(referenceSchema));
		}
		return referenceSchema -> {
			final String referenceName = referenceSchema.getName();
			return created.containsKey(referenceName) ?
				created.get(referenceName) : factory.apply(referenceSchema);
		};
	}

	/**
	 * Returns true for a reflected reference not attached to the reference it mirrors yet - a legal state of a schema
	 * being built, in which the reflection may be declared before the reference it mirrors exists. Such a reflection
	 * holds no data, so a summary has no option of it to describe, and it cannot answer anything it inherits: neither
	 * an inherited `faceted()` nor its group type, which is always the one of the reference it mirrors, and asking
	 * for either throws. The summary of all references therefore steps over it rather than fails a valid query.
	 *
	 * The recording of the `faceted()` flag in {@link #createProducerInternal} deliberately skips only the reflections
	 * inheriting the flag: a reflection stating its own `faceted()` answers it fine while not attached.
	 *
	 * @param referenceSchema the reference schema to test
	 * @return true when the reference is a reflection not attached yet
	 */
	private static boolean isUnattachedReflection(@Nonnull ReferenceSchemaContract referenceSchema) {
		return referenceSchema instanceof ReflectedReferenceSchemaContract reflectedReference
			&& !reflectedReference.isReflectedReferenceAvailable();
	}

	/**
	 * Resolves the reference schemas a nested `referenceContent` addresses. A single requirement may list several
	 * reference names at once - the multi-name form the GraphQL API and the query language both allow - and each of
	 * them has to be declared by the schema of the entity the summary fetches.
	 *
	 * @param referenceContent nested requirement naming at least one reference
	 * @param referencedSchema schema of the entity the summary fetches the references from
	 * @return schemas of the references the requirement addresses, in the order it lists them
	 * @throws ReferenceNotFoundException when the schema declares no reference of one of the listed names
	 */
	@Nonnull
	private static Collection<ReferenceSchemaContract> resolveNestedReferenceSchemas(
		@Nonnull ReferenceContent referenceContent,
		@Nonnull EntitySchemaContract referencedSchema
	) {
		final String[] nestedReferenceNames = referenceContent.getReferenceNames();
		final List<ReferenceSchemaContract> nestedReferenceSchemas = new ArrayList<>(nestedReferenceNames.length);
		for (final String nestedReferenceName : nestedReferenceNames) {
			nestedReferenceSchemas.add(
				referencedSchema.getReference(nestedReferenceName)
					.orElseThrow(() -> new ReferenceNotFoundException(nestedReferenceName, referencedSchema))
			);
		}
		return nestedReferenceSchemas;
	}

	/**
	 * Verify the fetch requirement for a given referenced type.
	 *
	 * @param entitySchema       the entity schema
	 * @param referencedType     function extracting the referenced type from a reference schema
	 * @param requirement        the fetch requirement to be verified
	 * @param extraResultPlanner the visitor used for extra result planning
	 * @param <T>                the type of the fetch requirement
	 * @return the verified fetch requirement
	 */
	@Nonnull
	static <T extends EntityFetchRequire> T verifyFetch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Function<ReferenceSchemaContract, String> referencedType,
		@Nonnull T requirement,
		@Nonnull Set<String> referencesDescribedBySpecificSummary,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		entitySchema.getReferences()
			.values()
			.stream()
			// a reference claimed by a reference-specific summary is not described by this generic one at all, so
			// its requirements must not be validated against that reference's schema either - a fetch that is valid
			// only for the references the generic form actually governs would otherwise be refused
			.filter(referenceSchema -> !referencesDescribedBySpecificSummary.contains(referenceSchema.getName()))
			// a reflection not attached yet holds no option and can tell neither whether it is faceted when it
			// inherits the flag, nor whether its group type is managed - see `isUnattachedReflection`
			.filter(referenceSchema -> !isUnattachedReflection(referenceSchema))
			.filter(
				referenceSchema -> extraResultPlanner
					.getEvitaRequest()
					.getScopes()
					.stream()
					.anyMatch(referenceSchema::isFacetedInScope)
			)
			.forEach(referenceSchema -> {
				         final String referencedEntityType = referencedType.apply(referenceSchema);
				         if (referencedEntityType != null) {
					         final EntitySchemaContract referencedSchema = extraResultPlanner.getSchema(referencedEntityType);
					         final EntityContentRequire[] requirements = requirement.getRequirements();
					         String[] missingLocalizedAttributes = null;
					         String[] missingLocalizedAssociatedData = null;
					         for (EntityContentRequire require : requirements) {
						         try {
							         if (require instanceof AttributeContent attributeContent) {
								         AttributeContentTranslator.verifyAttributes(
									         referencedSchema, null, referencedSchema, attributeContent, extraResultPlanner
								         );
							         } else if (require instanceof AssociatedDataContent associatedDataContent) {
								         AssociatedDataContentTranslator.verifyAssociatedData(
									         associatedDataContent, referencedSchema, extraResultPlanner
								         );
							         } else if (require instanceof ReferenceContent referenceContent) {
								         // a `referenceContent` may address several references at once, and every name it
								         // lists has to be declared by the referenced entity's schema
								         final Collection<ReferenceSchemaContract> referencedEntityReferenceSchemas =
									         referenceContent.isAllRequested()
										         ?
										         referencedSchema.getReferences().values()
										         :
											         resolveNestedReferenceSchemas(referenceContent, referencedSchema);
								         for (ReferenceSchemaContract referencedEntityReferenceSchema : referencedEntityReferenceSchemas) {
									         referenceContent.getAttributeContent()
										         .ifPresent(it -> AttributeContentTranslator.verifyAttributes(
											         referencedSchema, referencedEntityReferenceSchema,
											         referencedEntityReferenceSchema, it, extraResultPlanner
										         ));
								         }
							         } else if (require instanceof DataInLocales) {
								         // locales are specified here
								         return;
							         }
						         } catch (EntityLocaleMissingException ex) {
							         // gradually collect all missing localized attributes and associated data
							         missingLocalizedAttributes = missingLocalizedAttributes == null ?
								         ex.getAttributeNames() :
								         (ex.getAttributeNames() == null ?
								          missingLocalizedAttributes :
									         ArrayUtils.mergeArrays(missingLocalizedAttributes, ex.getAttributeNames())
								         );
							         missingLocalizedAssociatedData = missingLocalizedAssociatedData == null ?
								         ex.getAssociatedDataNames() :
								         (ex.getAssociatedDataNames() == null ?
								          missingLocalizedAssociatedData :
									         ArrayUtils.mergeArrays(missingLocalizedAssociatedData, ex.getAssociatedDataNames())
								         );
						         }
					         }
					         // if there are any missing localized attributes or associated data, throw an exception
					         if (missingLocalizedAttributes != null || missingLocalizedAssociatedData != null) {
						         throw new EntityLocaleMissingException(
							         missingLocalizedAttributes,
							         missingLocalizedAssociatedData
						         );
					         }
				         }
			         }
			);

		return requirement;
	}

	@Nullable
	@Override
	public ExtraResultProducer createProducer(
		@Nonnull ReferenceSummary referenceSummary,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		final EntitySchemaContract schema = extraResultPlanner.getSchema();
		final Set<Scope> scopes = extraResultPlanner.getProcessingScope().getScopes();
		final ExtraResultProducer producer = createProducerInternal(
			referenceSummary.getStatisticsDepth(),
			referenceSummary.getReferenceEntityRequirement().orElse(null),
			referenceSummary.getGroupEntityRequirement().orElse(null),
			referenceSummary.getFilterBy().orElse(null),
			referenceSummary.getFilterGroupBy().orElse(null),
			referenceSummary.getOrderBy().orElse(null),
			referenceSummary.getOrderGroupBy().orElse(null),
			collectReferencesWithRequestedHistograms(referenceSummary, schema, scopes),
			ReferenceSummaryAdapter.INSTANCE,
			extraResultPlanner
		);
		// pre-register so histogram child translators can locate the producer via
		// findExistingProducer; the planner's post-return registration via
		// ExtraResultPlanningVisitor.visit becomes a no-op (registerProducer is idempotent)
		extraResultPlanner.registerProducer(producer);
		// dispatch nested histogramStatistics children to their translator. This container is a
		// SelfTraversingTranslator, so children are not walked automatically. The all-references
		// form is applies-where-defined — references that do not declare a requested histogram in
		// every active scope are silently skipped; only requested names declared on **no**
		// reference in the schema raise EvitaInvalidUsageException (typo guard).
		for (final RequireConstraint child : referenceSummary.getChildren()) {
			if (child instanceof ReferenceHistogramStatistics histogramConstraint) {
				dispatchHistogramToMatchingReferences(
					histogramConstraint, schema, scopes, extraResultPlanner
				);
			}
		}
		return producer;
	}

	/**
	 * Collects the names of the references the histograms the summary of all references requests are computed for:
	 * every reference declaring at least one of the requested histograms in every processing scope - exactly the
	 * references {@link #dispatchHistogramToMatchingReferences} dispatches the requests to.
	 *
	 * The summary describes such a reference for its histograms whether the reference is faceted or not, and the
	 * producer resolves the predicates and the sorters the summary derives from its generic filters and orderings for it
	 * when it computes them - so those of these references have to be created while the query is planned too, see
	 * {@link #createForSummarizedReferences}.
	 *
	 * @param referenceSummary the summary of all references
	 * @param schema           the schema of the summarized entity type
	 * @param scopes           the processing scopes of the summary
	 * @return names of the references carrying a requested histogram, empty when the summary requests none
	 */
	@Nonnull
	private static Set<String> collectReferencesWithRequestedHistograms(
		@Nonnull ReferenceSummary referenceSummary,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Set<Scope> scopes
	) {
		Set<String> referencesWithHistograms = null;
		for (final RequireConstraint child : referenceSummary.getChildren()) {
			if (child instanceof ReferenceHistogramStatistics histogramConstraint) {
				for (final ReferenceSchemaContract referenceSchema : schema.getReferences().values()) {
					for (final String name : histogramConstraint.getIndexNames()) {
						if (isApplicableInAllScopes(referenceSchema, name, scopes)) {
							if (referencesWithHistograms == null) {
								referencesWithHistograms = CollectionUtils.createHashSet(schema.getReferences().size());
							}
							referencesWithHistograms.add(referenceSchema.getName());
							break;
						}
					}
				}
			}
		}
		return referencesWithHistograms == null ? Set.of() : referencesWithHistograms;
	}

	/**
	 * Dispatches the constraint to {@link ReferenceHistogramStatisticsTranslator} once per reference that
	 * is **applicable** for at least one of the requested histogram names. A `(reference, name)` pair is
	 * applicable when the reference declares the histogram in every currently active scope. References
	 * with no applicable name are silently skipped; references with a strict subset of applicable names
	 * are dispatched a *narrowed* {@link ReferenceHistogramStatistics} carrying only the names they
	 * actually declare, so the inner translator's strict per-scope check never trips on a missing
	 * definition for the all-references fan-out.
	 *
	 * Typo guard: every requested histogram name must be applicable to at least one reference in the
	 * schema. A name applicable to no reference (e.g. a misspelling) raises
	 * {@link EvitaInvalidUsageException} rather than silently producing nothing — that's the only error
	 * the broad form surfaces. Strict per-reference validation is the responsibility of the per-reference
	 * form ({@link io.evitadb.api.query.require.ReferenceSummaryOfReference}); scope subsetting belongs
	 * to {@link io.evitadb.api.query.require.RequireInScope}.
	 */
	private static void dispatchHistogramToMatchingReferences(
		@Nonnull ReferenceHistogramStatistics constraint,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Set<Scope> scopes,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		final String[] requestedNames = constraint.getIndexNames();
		final Collection<ReferenceSchemaContract> references = schema.getReferences().values();

		// Build per-reference applicable-name lists in iteration order; track which requested names
		// landed on at least one reference so the post-loop typo guard can flag misspellings.
		final List<ReferenceDispatchEntry> dispatchPlan = new ArrayList<>(references.size());
		final Set<String> matchedNames = CollectionUtils.createHashSet(requestedNames.length);
		for (final ReferenceSchemaContract referenceSchema : references) {
			List<String> applicableNames = null;
			boolean fullyCovered = true;
			for (final String name : requestedNames) {
				if (isApplicableInAllScopes(referenceSchema, name, scopes)) {
					if (applicableNames == null) {
						applicableNames = new ArrayList<>(requestedNames.length);
					}
					applicableNames.add(name);
					matchedNames.add(name);
				} else {
					fullyCovered = false;
				}
			}
			if (applicableNames != null) {
				dispatchPlan.add(new ReferenceDispatchEntry(referenceSchema, applicableNames, fullyCovered));
			}
		}

		// Typo guard — the broad form silently skips references that don't declare the histogram, but
		// a name that no reference declares is a user mistake and aborts query planning. The check
		// must operate on name presence (not array length) because `requestedNames` may carry
		// duplicates — comparing sizes would falsely throw when the same valid name is listed
		// multiple times. The `unknown` set is lazily allocated on the first miss so the happy
		// path pays no allocation, matching the lazy-init pattern used for `applicableNames`.
		Set<String> unknown = null;
		for (final String name : requestedNames) {
			if (!matchedNames.contains(name)) {
				if (unknown == null) {
					unknown = CollectionUtils.createLinkedHashSet(requestedNames.length);
				}
				unknown.add(name);
			}
		}
		if (unknown != null) {
			throw new EvitaInvalidUsageException(
				"Histogram " + (unknown.size() == 1 ? "name " : "names ") + unknown +
					(unknown.size() == 1 ? " is" : " are") +
					" not defined on any reference of entity `" + schema.getName() +
					"` in scope" + (scopes.size() == 1 ? " " : "s ") + scopes + "."
			);
		}

		final int bucketCount = constraint.getRequestedBucketCount();
		final HistogramBehavior behavior = constraint.getBehavior();
		final EntityFetch entityFetch = constraint.getEntityFetch().orElse(null);
		for (final ReferenceDispatchEntry entry : dispatchPlan) {
			// reuse the original constraint when this reference declares every requested name —
			// avoids an unnecessary clone for the common single-reference / fully-covered case.
			// `fullyCovered` is tracked explicitly during dispatch-plan construction so this
			// decision stays orthogonal to whether `applicableNames` carries duplicates.
			final ReferenceHistogramStatistics narrowed =
				entry.fullyCovered()
					? constraint
					: new ReferenceHistogramStatistics(
						bucketCount, behavior, entityFetch,
						entry.applicableNames().toArray(new String[0])
					);
			extraResultPlanner.executeInContext(
				narrowed,
				entry::referenceSchema,
				() -> null,
				() -> {
					narrowed.accept(extraResultPlanner);
					return null;
				}
			);
		}
	}

	/**
	 * Returns true when the reference declares the named histogram in every currently active scope.
	 * The broad form skips references that don't fully cover the active scope set — partial coverage
	 * (declared in some scopes but not others) is treated identically to "not declared at all" so
	 * fan-out behaviour stays scope-symmetric. Strict per-scope diagnostics remain the responsibility
	 * of the per-reference form.
	 */
	private static boolean isApplicableInAllScopes(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String histogramName,
		@Nonnull Set<Scope> scopes
	) {
		if (scopes.isEmpty()) {
			return false;
		}
		for (final Scope scope : scopes) {
			if (referenceSchema.getHistogramIndexDefinition(scope, histogramName) == null) {
				return false;
			}
		}
		return true;
	}

	/**
	 * One dispatched per applicable reference: the schema, the subset of requested histogram names
	 * the reference actually declares in every active scope, and whether that subset covers every
	 * requested name (i.e. no requested name was skipped for this reference). The `fullyCovered`
	 * flag drives the constraint-reuse fast path independently of duplicate handling in
	 * `applicableNames`.
	 */
	private record ReferenceDispatchEntry(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull List<String> applicableNames,
		boolean fullyCovered
	) {
	}

}
