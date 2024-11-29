/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.DataInLocales;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import io.evitadb.core.query.extraResult.translator.reference.AssociatedDataContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.AttributeContentTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.collection.BitmapIntoBitmapCollector;
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

import static io.evitadb.core.query.extraResult.translator.facet.FacetSummaryOfReferenceTranslator.*;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link FacetSummary} to {@link FacetSummaryProducer}.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetSummaryTranslator implements RequireConstraintTranslator<FacetSummary>, SelfTraversingTranslator {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull FacetSummary facetSummary, @Nonnull ExtraResultPlanningVisitor extraResultPlanner) {
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

		final EntityFetch facetEntityRequirement = facetSummary.getFacetEntityRequirement()
			.map(it -> verifyFetch(entitySchema, referenceSchema -> referenceSchema.isReferencedEntityTypeManaged() ? referenceSchema.getReferencedEntityType() : null, it, extraResultPlanner))
			.orElse(null);
		final EntityGroupFetch groupEntityRequirement = facetSummary.getGroupEntityRequirement()
			.map(it -> verifyFetch(entitySchema, referenceSchema -> referenceSchema.isReferencedGroupTypeManaged() ? referenceSchema.getReferencedGroupType() : null, it, extraResultPlanner))
			.orElse(null);

		facetSummaryProducer.requireDefaultFacetSummary(
			facetSummary.getStatisticsDepth(),
			referenceSchema -> facetSummary.getFilterBy().map(it -> createFacetPredicate(extraResultPlanner, it, referenceSchema, false)).orElse(null),
			referenceSchema -> facetSummary.getFilterGroupBy().map(it -> createFacetGroupPredicate(extraResultPlanner, it, referenceSchema, false)).orElse(null),
			referenceSchema -> facetSummary.getOrderBy().map(it -> createFacetSorter(extraResultPlanner, it, findLocale(facetSummary.getFilterBy().orElse(null)), extraResultPlanner, referenceSchema, false)).orElse(null),
			referenceSchema -> facetSummary.getOrderGroupBy().map(it -> createFacetGroupSorter(extraResultPlanner, it, findLocale(facetSummary.getFilterGroupBy().orElse(null)), extraResultPlanner, referenceSchema, false)).orElse(null),
			facetEntityRequirement,
			groupEntityRequirement
		);
		return facetSummaryProducer;
	}

	/**
	 * Verify the fetch requirement for a given referenced type.
	 *
	 * @param referencedType       the type to be referenced
	 * @param requirement          the fetch requirement to be verified
	 * @param extraResultPlanner   the visitor used for extra result planning
	 * @return the verified fetch requirement
	 * @param <T>                  the type of the fetch requirement
	 */
	@Nonnull
	private static <T extends EntityFetchRequire> T verifyFetch(
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
								final Collection<ReferenceSchemaContract> referencedEntityReferenceSchemas = referenceContent.isAllRequested() ?
									referencedSchema.getReferences().values() :
									List.of(
										referencedSchema.getReference(referenceContent.getReferenceName())
											.orElseThrow(() -> new ReferenceNotFoundException(referenceContent.getReferenceName(), referencedSchema))
									);
								for (ReferenceSchemaContract referencedEntityReferenceSchema : referencedEntityReferenceSchemas) {
									referenceContent.getAttributeContent()
										.ifPresent(it -> AttributeContentTranslator.verifyAttributes(
											referencedSchema, referencedEntityReferenceSchema, referencedEntityReferenceSchema, it, extraResultPlanner
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

}
