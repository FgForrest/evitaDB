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

package io.evitadb.core.query.filter.translator.behavioral;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.InScope;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link InScope} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InScopeTranslator implements FilteringConstraintTranslator<InScope>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Formula translate(@Nonnull InScope inScope, @Nonnull FilterByVisitor filterByVisitor) {
		final Set<Scope> requestedScopes = filterByVisitor.getScopes();
		final Scope scopeToUse = inScope.getScope();
		Assert.isTrue(
			requestedScopes.contains(scopeToUse),
			"Scope `" + scopeToUse + "` used in `inScope` filter container was not requested by `scope` constraint!"
		);

		filterByVisitor.registerFormulaPostProcessor(
			InScopeFormulaPostProcessor.class,
			() -> new InScopeFormulaPostProcessor(requestedScopes, filterByVisitor::getSuperSetFormula)
		);

		return filterByVisitor.getProcessingScope()
			.doWithScope(
				EnumSet.of(scopeToUse),
				() -> {
					for (FilterConstraint innerConstraint : inScope.getChildren()) {
						innerConstraint.accept(filterByVisitor);
					}
					return new ScopeContainerFormula(
						scopeToUse,
						filterByVisitor.getCollectedFormulasOnCurrentLevel()
					);
				}
			);
	}

	/**
	 * This FormulaPostProcessor handles important aspect of the `scope` filter when both indexed and non-indexed data
	 * are requested. Imagine you have a schema with an attribute `code` that is indexed in the live scope and not indexed
	 * in the archived scope. If you query the entities by the `code` attribute in both scopes in the following way
	 *
	 * ```evitaql
	 * query(
	 *     collection("entity"),
	 *     filterBy(
	 *         entityPrimaryKeyInSet(1, 2, 3),
	 *         inScope(LIVE, attributeIs("code", NOT_NULL)),
	 *         scope(LIVE)
	 *     )
	 * )
	 * ```
	 *
	 * You'd expect to get the entities with primary keys 1, 2 and 3 from the live scope. However, if you archive some of these
	 * entities, say 1 and 2, and repeat the same query using the `scope(LIVE, ARCHIVED)` filter, you'd only get the entity
	 * with primary key 3. This is because the `code` attribute is not indexed in the archived scope and the query would be
	 * translated into a conjunction of `1`, `2`, `3` and non-null keys from the live scope - which is only the entity with
	 * primary key `3` - and this would result in a result set containing only the entity with primary key `3`.
	 *
	 * This is not what developers expect when working with archived entities - they expect the query to return all
	 * the entities that match all the constraints that are applicable (indexed) for a particular scope, and to ignore
	 * the constraints that cannot be computed for that scope. So, in practice, they expect the query to be translated:
	 *
	 * ```
	 * OR(
	 *     AND(                 // SCOPE(ARCHIVED)
	 *         [1, 2],          // SUPER SET OF ALL ARCHIVED ENTITIES
	 *         [1, 2, 3]        // CONSTANT: ENTITY_PRIMARY_KEY_IN_SET(1, 2, 3)
	 *     ),
	 *     AND(                  // SCOPE(LIVE)
	 *         [3],              // SUPER SET OF ALL LIVE ENTITIES
	 *         [1, 2, 3],        // CONSTANT: ENTITY_PRIMARY_KEY_IN_SET(1, 2, 3)
	 *         [3]               // NOT_NULL("code"),
	 *     )
	 * )
	 * ```
	 *
	 * This means that when both scopes are queried, the post processor constructs two complement queries, each containing
	 * only the constraints that apply to that scope, and ignoring other constraints.
	 *
	 * This way we can enforce the rule of having prepared index (attribute "code" doesn't have index for archived scope)
	 * for each queried constraint without need for creating two different queries.
	 *
	 * @see FormulaPostProcessor
	 */
	@RequiredArgsConstructor
	private static final class InScopeFormulaPostProcessor implements FormulaPostProcessor {
		/**
		 * The set of scopes that were requested by the input query.
		 */
		private final Set<Scope> requestedScopes;
		/**
		 * Supplier that provides the super set formula.
		 */
		private final Supplier<Formula> superSetFormulaSupplier;
		/**
		 * Final formula that will be returned.
		 */
		private Formula finalFormula;

		@Nonnull
		@Override
		public Formula getPostProcessedFormula() {
			return Objects.requireNonNull(this.finalFormula);
		}

		@Override
		public void visit(@Nonnull Formula formula) {
			this.finalFormula = FormulaFactory.or(
				this.requestedScopes.stream()
					.map(
						scope -> FormulaCloner.clone(
							formula,
							examinedFormula -> {
								// if the formula is a ScopeContainerFormula and the scope matches, return the inner formulas
								// otherwise skip the container including the inner formulas
								if (examinedFormula instanceof ScopeContainerFormula scf) {
									return scf.getScope() == scope ?
										FormulaFactory.and(scf.getInnerFormulas()) : null;
								} else {
									return examinedFormula;
								}
							}
						)
					)
					.map(
						// if the result formula is empty - it means that for particular scope there are no constraints set
						// we need to use super set formula instead - i.e. all entities in the scope match the "zero" constraints
						it -> it == null ? this.superSetFormulaSupplier.get() : it
					)
					.filter(it -> it != EmptyFormula.INSTANCE)
					.toArray(Formula[]::new)
			);
		}
	}

}
