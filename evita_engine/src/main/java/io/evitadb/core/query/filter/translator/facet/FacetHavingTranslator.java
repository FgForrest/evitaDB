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

package io.evitadb.core.query.filter.translator.facet;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link FacetHaving} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetHavingTranslator implements FilteringConstraintTranslator<FacetHaving>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Formula translate(@Nonnull FacetHaving facetHaving, @Nonnull FilterByVisitor filterByVisitor) {
		final EntitySchemaContract entitySchema = filterByVisitor.getProcessingScope().getEntitySchemaOrThrowException();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(facetHaving.getReferenceName());
		isTrue(
			filterByVisitor.getScopes().stream().anyMatch(referenceSchema::isFacetedInScope),
			() -> new ReferenceNotFacetedException(facetHaving.getReferenceName(), entitySchema)
		);

		final List<Formula> collectedFormulas = filterByVisitor.collectFromIndexes(
			entityIndex -> {
				final Bitmap facetIds = filterByVisitor.getReferencedRecordIdFormula(
					entitySchema,
					referenceSchema,
					filterBy(facetHaving.getChildren())
				).compute();
				// first collect all formulas
				return entityIndex.getFacetReferencingEntityIdsFormula(
					facetHaving.getReferenceName(),
					(groupId, theFacetIds, recordIdBitmaps) -> {
						if ((referenceSchema.isReferencedGroupTypeManaged() || groupId == null) && filterByVisitor.isFacetGroupConjunction(referenceSchema, groupId)) {
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
					facetIds
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
			.map(FacetGroupFormula.class::cast)
			.collect(
				Collectors.groupingBy(
					it -> {
						final Integer groupId = it.getFacetGroupId();
						if (groupId != null) {
							if (referenceSchema.isReferencedGroupTypeManaged()) {
								// OR relation is requested for facets of this group
								if (filterByVisitor.isFacetGroupDisjunction(referenceSchema, groupId)) {
									return Or.class;
									// NOT relation is requested for facets of this group
								} else if (filterByVisitor.isFacetGroupNegation(referenceSchema, groupId)) {
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
					ArrayUtils.insertRecordIntoArray(andFormula, orFormula.getInnerFormulas(), orFormula.getInnerFormulas().length)
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

	public record GroupKey(@Nullable Integer groupId) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			GroupKey groupKey = (GroupKey) o;
			return Objects.equals(groupId, groupKey.groupId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(groupId);
		}

	}

}
