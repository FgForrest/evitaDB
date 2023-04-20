/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.extraResult.translator.facet;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.FacetSummaryOfReference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.ReferenceNotFacetedException;
import io.evitadb.core.exception.ReferenceNotFoundException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.facet.producer.FacetSummaryProducer;
import io.evitadb.core.query.extraResult.translator.facet.producer.FilteringFormulaPredicate;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static io.evitadb.utils.Assert.isTrue;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link FacetSummaryOfReference} to {@link FacetSummaryProducer}.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to {@link ExtraResultProducer#fabricate(List)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetSummaryOfReferenceTranslator implements RequireConstraintTranslator<FacetSummaryOfReference>, SelfTraversingTranslator {

	@Override
	public ExtraResultProducer apply(FacetSummaryOfReference facetSummaryOfReference, ExtraResultPlanningVisitor extraResultPlanner) {
		final String referenceName = facetSummaryOfReference.getReferenceName();
		final EntitySchemaContract entitySchema = extraResultPlanner.getSchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));
		isTrue(referenceSchema.isFaceted(), () -> new ReferenceNotFacetedException(referenceName, entitySchema));

		// find user filters that enclose variable user defined part
		final Set<Formula> formulaScope = extraResultPlanner.getUserFilteringFormula().isEmpty() ?
			Set.of(extraResultPlanner.getFilteringFormula()) :
			extraResultPlanner.getUserFilteringFormula();
		// find all requested facets
		final Map<String, IntSet> requestedFacets = formulaScope
			.stream()
			.flatMap(it -> FormulaFinder.find(it, FacetGroupFormula.class, LookUp.SHALLOW).stream())
			.collect(
				Collectors.groupingBy(
					FacetGroupFormula::getReferenceName,
					Collectors.mapping(
						FacetGroupFormula::getFacetIds,
						new IntArrayToIntSetCollector()
					)
				)
			);
		// collect all facet statistics
		final TargetIndexes indexSetToUse = extraResultPlanner.getIndexSetToUse();
		final List<Map<String, FacetReferenceIndex>> facetIndexes = indexSetToUse.getIndexesOfType(EntityIndex.class)
			.stream()
			.map(EntityIndex::getFacetingEntities)
			.collect(Collectors.toList());

		// find existing FacetSummaryProducer for potential reuse
		FacetSummaryProducer facetSummaryProducer = extraResultPlanner.findExistingProducer(FacetSummaryProducer.class);
		if (facetSummaryProducer == null) {
			// now create the producer instance that has all pointer necessary to compute result
			// all operations above should be relatively cheap comparing to final result computation, that is deferred
			// to FacetSummaryProducer#fabricate method
			facetSummaryProducer = new FacetSummaryProducer(
				extraResultPlanner.getQueryContext(),
				extraResultPlanner.getFilteringFormula(),
				extraResultPlanner.getFilteringFormulaWithoutUserFilter(),
				facetIndexes,
				requestedFacets
			);
		}

		facetSummaryProducer.requireReferenceFacetSummary(
			referenceSchema,
			facetSummaryOfReference.getFacetStatisticsDepth(),
			facetSummaryOfReference.getFilterBy().map(it -> createFacetPredicate(it, extraResultPlanner, referenceSchema)).orElse(null),
			facetSummaryOfReference.getFilterGroupBy().map(it -> createFacetGroupPredicate(it, extraResultPlanner, referenceSchema)).orElse(null),
			facetSummaryOfReference.getOrderBy().map(it -> createFacetSorter(it, extraResultPlanner, referenceSchema)).orElse(null),
			facetSummaryOfReference.getOrderGroupBy().map(it -> createFacetGroupSorter(it, extraResultPlanner, referenceSchema)).orElse(null),
			facetSummaryOfReference.getFacetEntityRequirement().orElse(null),
			facetSummaryOfReference.getGroupEntityRequirement().orElse(null)
		);
		return facetSummaryProducer;
	}

	@Nullable
	static IntPredicate createFacetGroupPredicate(
		@Nullable FilterGroupBy filterGroupBy,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		return new FilteringFormulaPredicate(
			extraResultPlanner.getQueryContext(),
			new FilterBy(filterGroupBy.getChildren()),
			referenceSchema.getReferencedGroupType(),
			() -> "Facet summary of `" + referenceSchema.getName() + "` group filter: " + filterGroupBy
		);
	}

	@Nullable
	static IntPredicate createFacetPredicate(
		@Nullable FilterBy filterBy,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		return new FilteringFormulaPredicate(
			extraResultPlanner.getQueryContext(),
			filterBy,
			referenceSchema.getReferencedEntityType(),
			() -> "Facet summary of `" + referenceSchema.getName() + "` facet filter: " + filterBy
		);
	}

	@Nullable
	static Sorter createFacetSorter(
		@Nullable OrderBy orderBy,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		Assert.isTrue(
			referenceSchema.isReferencedEntityTypeManaged(),
			() -> "Facets of reference `" + referenceSchema.getName() + "` cannot be sorted because they relate to " +
				"non-managed entity type `" + referenceSchema.getReferencedEntityType() + "`."
		);
		return extraResultPlanner.createSorter(
			orderBy,
			extraResultPlanner.getGlobalEntityIndex(referenceSchema.getReferencedEntityType()),
			() -> "Facet summary `" + referenceSchema.getName() + "` facet ordering: " + orderBy
		);
	}

	@Nullable
	static Sorter createFacetGroupSorter(
		@Nullable OrderGroupBy orderBy,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		Assert.isTrue(
			referenceSchema.isReferencedGroupTypeManaged(),
			() -> "Facet groups of reference `" + referenceSchema.getName() + "` cannot be sorted because they relate to " +
				"non-managed entity type `" + referenceSchema.getReferencedGroupType() + "`."
		);
		return extraResultPlanner.createSorter(
			orderBy,
			extraResultPlanner.getGlobalEntityIndex(referenceSchema.getReferencedGroupType()),
			() -> "Facet summary `" + referenceSchema.getName() + "` group ordering: " + orderBy
		);
	}

	/**
	 * Collector helps to accumulate arrays of possible duplicated integers in {@link IntSet}.
	 */
	private static class IntArrayToIntSetCollector implements Collector<int[], IntHashSet, IntSet> {

		@Override
		public Supplier<IntHashSet> supplier() {
			return IntHashSet::new;
		}

		@Override
		public BiConsumer<IntHashSet, int[]> accumulator() {
			return (acc, recs) -> Arrays.stream(recs).forEach(acc::add);
		}

		@Override
		public BinaryOperator<IntHashSet> combiner() {
			return (left, right) -> {
				left.addAll(right);
				return left;
			};
		}

		@Override
		public Function<IntHashSet, IntSet> finisher() {
			return acc -> acc;
		}

		@Override
		public Set<Characteristics> characteristics() {
			return Set.of(
				Characteristics.UNORDERED,
				Characteristics.CONCURRENT
			);
		}
	}
}
