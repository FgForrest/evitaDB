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

package io.evitadb.core.query.filter.translator.entity;

import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.price.termination.PriceWrappingFormula;
import io.evitadb.core.query.algebra.reference.ReferencedEntityIndexPrimaryKeyTranslatingFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.behavioral.FilterInScopeTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.index.Index;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link EntityPrimaryKeyInSet} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntityPrimaryKeyInSetTranslator implements FilteringConstraintTranslator<EntityPrimaryKeyInSet> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull EntityPrimaryKeyInSet entityPrimaryKeyInSet, @Nonnull FilterByVisitor filterByVisitor) {
		Assert.notNull(filterByVisitor.getSchema(), "Schema must be known!");
		filterByVisitor.registerFormulaPostProcessorAfter(
			SuperSetMatchingPostProcessor.class,
			() -> new SuperSetMatchingPostProcessor(filterByVisitor),
			FilterInScopeTranslator.InScopeFormulaPostProcessor.class
		);

		final int[] primaryKeys = entityPrimaryKeyInSet.getPrimaryKeys();
		if (ArrayUtils.isEmpty(primaryKeys)) {
			return EmptyFormula.INSTANCE;
		} else {
			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			final Class<? extends Index<?>> indexType = processingScope.getIndexType();
			final ConstantFormula standardResult = new ConstantFormula(
				new BaseBitmap(primaryKeys)
			);
			if (indexType == ReferencedTypeEntityIndex.class) {
				return FormulaFactory.or(
					processingScope
						.getIndexStream()
						.map(ReferencedTypeEntityIndex.class::cast)
						.map(it -> new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
							     Objects.requireNonNull(processingScope.getReferenceSchema()),
							     filterByVisitor::getGlobalEntityIndexIfExists,
							     it,
							     standardResult,
							     processingScope.getScopes()
						     )
						)
						.toArray(Formula[]::new)
				);
			} else {
				return standardResult;
			}
		}
	}

	/**
	 * This post processor will merge the result formula with super set formulas if the index is not queried by other
	 * constraints. If we don't do this, the formula may return primary keys that are not part of the index, just
	 * because they were part of the input filtering constraint. Consider query:
	 *
	 * filterBy: entityPrimaryKeyInSet(1, 2, 3)
	 *
	 * applied on index containing primary keys:
	 *
	 * [100, 200, 300]
	 *
	 * In this situation the result formula should be empty and not [1, 2, 3] as it would be without this post processor.
	 */
	@RequiredArgsConstructor
	public static class SuperSetMatchingPostProcessor implements FormulaPostProcessor {
		/**
		 * The filter by visitor that is used to process the formula.
		 */
		protected final FilterByVisitor filterByVisitor;
		/**
		 * Flag that signalizes {@link #visit(Formula)} happens in conjunctive scope.
		 */
		protected boolean conjunctiveScope = true;
		/**
		 * Flag that signalizes that the target index is queried by other formulas in the conjunctive scope.
		 */
		protected boolean targetIndexQueriedByOtherConstraints = false;
		/**
		 * Contains the root of the final formula.
		 */
		private Formula resultFormula;

		@Nonnull
		@Override
		public Formula getPostProcessedFormula() {
			// if the index is queried by other constraints, we don't need to merge the super set formulas
			// because it's already more constrained than the super set
			if (this.targetIndexQueriedByOtherConstraints) {
				return this.resultFormula;
			} else if (this.resultFormula instanceof ScopeContainerFormula scf) {
				// if the result formula is a scope container, we need to merge the super set formula with the inner formula
				return FormulaFactory.and(
					this.filterByVisitor.getSuperSetFormula(scf.getScope()),
					scf
				);
			} else if (this.resultFormula instanceof OrFormula of &&
				of.getInnerFormulas().length > 0 &&
				Arrays.stream(of.getInnerFormulas()).allMatch(ScopeContainerFormula.class::isInstance)
			) {
				// if the result formula is an OR formula containing only scope containers,
				// we need to merge their respective super set formulas with the inner formulas
				return FormulaFactory.or(
					Arrays.stream(of.getInnerFormulas())
						.map(ScopeContainerFormula.class::cast)
						.map(scf -> FormulaFactory.and(
							this.filterByVisitor.getSuperSetFormula(scf.getScope()),
							scf
						))
						.toArray(Formula[]::new)
				);
			} else {
				// if the index is not queried by other constraints, we need to merge the super set formulas
				final Set<Scope> scopesNotCovered = EnumSet.noneOf(Scope.class);
				scopesNotCovered.addAll(this.filterByVisitor.getQueryContext().getScopes());
				final Set<Scope> scopesCoveredByIndexes = this.filterByVisitor.getEntityIndexStream()
					.map(it -> it.getIndexKey().scope())
					.collect(Collectors.toCollection(() -> EnumSet.noneOf(Scope.class)));
				final Formula superSetFormula;
				scopesNotCovered.removeAll(scopesCoveredByIndexes);
				// if all scopes are covered by indexes, we can use the super set formula directly
				if (scopesNotCovered.isEmpty()) {
					superSetFormula = this.filterByVisitor.getSuperSetFormula();
				} else {
					// otherwise, we need to combine it with the super set formula that covers particular scope
					superSetFormula = FormulaFactory.or(
						Stream.concat(
							Stream.of(this.filterByVisitor.getSuperSetFormula()),
							scopesNotCovered.stream()
								.map(this.filterByVisitor::getSuperSetFormula)
						).toArray(Formula[]::new)
					);
				}
				return FormulaFactory.and(
					superSetFormula,
					this.resultFormula
				);
			}
		}

		@Override
		public void visit(@Nonnull Formula formula) {
			// if the result formula is not set yet, set it to the current formula and return
			if (this.resultFormula == null) {
				this.resultFormula = formula;
			}

			final boolean formerConjunctiveScope = this.conjunctiveScope;
			try {
				if (!FilterByVisitor.isConjunctiveFormula(formula.getClass())) {
					this.conjunctiveScope = false;
				}
				if (formula instanceof final AttributeFormula attributeFormula) {
					this.targetIndexQueriedByOtherConstraints = this.targetIndexQueriedByOtherConstraints ||
						(!attributeFormula.isTargetsGlobalAttribute() && this.conjunctiveScope);
				} else if (formula instanceof PriceWrappingFormula) {
					this.targetIndexQueriedByOtherConstraints = this.targetIndexQueriedByOtherConstraints ||
						this.conjunctiveScope;
				}
				for (Formula innerFormula : formula.getInnerFormulas()) {
					innerFormula.accept(this);
				}
			} finally {
				this.conjunctiveScope = formerConjunctiveScope;
			}
		}

	}
}
