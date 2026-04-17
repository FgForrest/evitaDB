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
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryAdapter;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryResultAdapter;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator.*;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link ReferenceSummary} to
 * {@link ReferenceSummaryProducer}.
 * The producer instance has all pointers necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to
 * {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)} method.
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
		@Nonnull ReferenceSummaryResultAdapter<? extends ReferenceGroupStatistics> resultAdapter,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		final ProcessingScope processingScope = extraResultPlanner.getProcessingScope();
		final Set<Scope> scopes = processingScope.getScopes();
		final EntitySchemaContract entitySchema = processingScope.getEntitySchema()
			.orElseGet(extraResultPlanner::getSchema);

		// collect all facet statistics
		final TargetIndexes<?> indexSetToUse = extraResultPlanner.getIndexSetToUse();
		final List<Map<String, FacetReferenceIndex>> facetIndexes = indexSetToUse.getIndexStream(EntityIndex.class)
			.filter(index -> scopes.contains(index.getIndexKey().scope()))
			.map(EntityIndex::getFacetingEntities)
			.collect(Collectors.toList());

		final ReferenceSummaryProducer referenceSummaryProducer = findOrCreateProducer(
			facetIndexes, resultAdapter, extraResultPlanner
		);

		final EntityFetch facetEntityRequirement = referenceEntityRequirement != null ?
			verifyFetch(
				entitySchema,
				referenceSchema -> referenceSchema.isReferencedEntityTypeManaged() ?
					referenceSchema.getReferencedEntityType() : null,
				referenceEntityRequirement,
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
				extraResultPlanner
			) :
			null;

		referenceSummaryProducer.requireDefaultReferenceSummary(
			statisticsDepth,
			referenceSchema -> filterBy != null
				? createFacetPredicate(extraResultPlanner, filterBy, referenceSchema, false)
				: null,
			referenceSchema -> filterGroupBy != null
				? createFacetGroupPredicate(extraResultPlanner, filterGroupBy, referenceSchema, false)
				: null,
			referenceSchema -> orderBy != null
				?
				createFacetSorter(
					extraResultPlanner, orderBy, findLocale(filterBy),
					extraResultPlanner, referenceSchema, false
				)
				: null,
			referenceSchema -> orderGroupBy != null
				?
				createFacetGroupSorter(
					extraResultPlanner, orderGroupBy,
					findLocale(filterGroupBy), extraResultPlanner, referenceSchema, false
				)
				: null,
			facetEntityRequirement,
			groupEntityReq
		);
		return referenceSummaryProducer;
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
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		entitySchema.getReferences()
			.values()
			.stream()
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
								         final Collection<ReferenceSchemaContract> referencedEntityReferenceSchemas =
									         referenceContent.isAllRequested()
										         ?
										         referencedSchema.getReferences().values()
										         :
											         List.of(
												         referencedSchema.getReference(referenceContent.getReferenceName())
												         .orElseThrow(() -> new ReferenceNotFoundException(
													         referenceContent.getReferenceName(), referencedSchema))
											         );
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
		return createProducerInternal(
			referenceSummary.getStatisticsDepth(),
			referenceSummary.getReferenceEntityRequirement().orElse(null),
			referenceSummary.getGroupEntityRequirement().orElse(null),
			referenceSummary.getFilterBy().orElse(null),
			referenceSummary.getFilterGroupBy().orElse(null),
			referenceSummary.getOrderBy().orElse(null),
			referenceSummary.getOrderGroupBy().orElse(null),
			ReferenceSummaryAdapter.INSTANCE,
			extraResultPlanner
		);
	}

}
