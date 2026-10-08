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

import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.core.query.QueryPlanner.EnclosingContainerRelation;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.VirtualPartitionRows;
import io.evitadb.core.query.filter.VirtualPartitionRows.InScopeReading;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityPrimaryKeyInSetTranslator;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link FilterInScope} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FilterInScopeTranslator implements FilteringConstraintTranslator<FilterInScope>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Formula translate(@Nonnull FilterInScope inScope, @Nonnull FilterByVisitor filterByVisitor) {
		final Set<Scope> requestedScopes = filterByVisitor.getScopes();
		final Scope scopeToUse = inScope.getScope();
		Assert.isTrue(
			requestedScopes.contains(scopeToUse),
			"Scope `" + scopeToUse + "` used in `inScope` filter container was not requested by `scope` constraint!"
		);

		final VirtualPartitionRows virtualPartitionRows =
			filterByVisitor.getProcessingScope().getVirtualPartitionRows();
		if (virtualPartitionRows != null) {
			return translateForVirtualPartitionRows(inScope, virtualPartitionRows, filterByVisitor);
		}

		// a nested evaluation may process fewer scopes than the query requested - the parent filter of a hierarchy
		// constraint is resolved in the tree of one scope at a time - and the container must restrict only those
		final Set<Scope> processingScopes = filterByVisitor.getProcessingScope().getScopes();
		filterByVisitor.registerFormulaPostProcessorBefore(
			InScopeFormulaPostProcessor.class,
			() -> new InScopeFormulaPostProcessor(processingScopes, filterByVisitor::getSuperSetFormula),
			EntityPrimaryKeyInSetTranslator.SuperSetMatchingPostProcessor.class
		);

		if (!processingScopes.contains(scopeToUse)) {
			// the container restricts a scope this evaluation does not process, so it restricts nothing here - its
			// constraints are not evaluated and the post-processor replaces it with every entity of each processed
			// scope
			return new ScopeContainerFormula(scopeToUse);
		}

		return filterByVisitor.getProcessingScope()
			.doWithScope(
				EnumSet.of(scopeToUse),
				() -> {
					for (FilterConstraint innerConstraint : inScope.getChildren()) {
						innerConstraint.accept(filterByVisitor);
					}
					final Formula[] collectedFormulas = filterByVisitor.getCollectedFormulasOnCurrentLevel();
					if (Arrays.stream(collectedFormulas).noneMatch(FutureNotFormula.class::isInstance)) {
						return new ScopeContainerFormula(scopeToUse, collectedFormulas);
					}
					// a negation must be resolved by the container that collects it - the levels above see this
					// container, not the placeholder inside it - and standing alone it subtracts from every entity of
					// the container's scope, which is all the container restricts
					return new ScopeContainerFormula(
						scopeToUse,
						FutureNotFormula.postProcess(
							collectedFormulas,
							EnclosingContainerRelation.CONJUNCTION,
							() -> filterByVisitor.getSuperSetFormula(scopeToUse)
						)
					);
				}
			);
	}

	/**
	 * Answers the container for rows of registered single-owner partitions of one scope - see
	 * {@link VirtualPartitionRows}. Every row lives in the scope of its partition, so the container is decided for all
	 * of them at once, without the post-processor, which would read the type-level index of the rows' scope as the
	 * whole answer and fill the other requested scopes with records of another kind:
	 *
	 * - naming the scope of the rows, the container is its children, translated in that scope;
	 * - naming another scope, it is what the route the rows are answered for makes of a constraint confined to another
	 *   scope - every row, or none (see {@link VirtualPartitionRows.InScopeReading}).
	 *
	 * A container decided without its children still hands their locales to the ordering of the fetched references,
	 * as the visit of the index objects does - see {@link #applyLocalesToNestedQueryComparator}.
	 *
	 * @param inScope         the container
	 * @param rows            the rows answered
	 * @param filterByVisitor the visitor translating the body
	 * @return the formula over the partition primary keys of the rows satisfying the container
	 */
	@Nonnull
	private static Formula translateForVirtualPartitionRows(
		@Nonnull FilterInScope inScope,
		@Nonnull VirtualPartitionRows rows,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final Scope scopeToUse = inScope.getScope();
		if (scopeToUse != rows.getScope()) {
			applyLocalesToNestedQueryComparator(inScope, filterByVisitor.getProcessingScope());
			return rows.getInScopeReading() == InScopeReading.OTHER_SCOPES_MET ?
				filterByVisitor.getSuperSetFormula() : EmptyFormula.INSTANCE;
		}
		return filterByVisitor.getProcessingScope()
			.doWithScope(
				EnumSet.of(scopeToUse),
				() -> {
					for (FilterConstraint innerConstraint : inScope.getChildren()) {
						innerConstraint.accept(filterByVisitor);
					}
					final Formula[] innerFormulas = filterByVisitor.getCollectedFormulasOnCurrentLevel();
					// the children are a conjunction, settled against the type-level index as any other level is
					return innerFormulas.length == 0 ?
						filterByVisitor.getSuperSetFormula() :
						FutureNotFormula.postProcess(
							innerFormulas, EnclosingContainerRelation.CONJUNCTION, filterByVisitor::getSuperSetFormula
						);
				}
			);
	}

	/**
	 * Hands the comparator of the ordering of the fetched references the locales of the `entityLocaleEquals` inside
	 * a container the virtual rows decide without visiting its children. The index objects visit the children of every
	 * `inScope`, whichever scope it names, and {@link io.evitadb.core.query.filter.translator.entity.EntityLocaleEqualsTranslator}
	 * sets its locale on the comparator as a side effect of the visit - so a locale confined to a scope the rows are
	 * not in still decides the language `referenceContent(..., orderBy(entityProperty(...)))` orders by. Translating
	 * the children against the rows would not do: they are confined to another scope, whose indexes the rows know
	 * nothing about.
	 *
	 * The walk follows the visit of the index objects: the children in their order, so the last locale wins, through
	 * the containers translated within the same processing scope - the logical ones and a nested `inScope` - and never
	 * into a container that answers its body in a nested query of its own, such as `entityHaving`, whose locale is not
	 * the comparator's. A constraint the processing scope suppresses is skipped, as the visit skips it.
	 *
	 * Costs nothing when no ordering of the fetched references is requested, otherwise one pass over the container.
	 *
	 * @param container       the container whose children are not translated
	 * @param processingScope the processing scope of the rows
	 */
	private static void applyLocalesToNestedQueryComparator(
		@Nonnull ConstraintContainer<FilterConstraint> container,
		@Nonnull ProcessingScope<?> processingScope
	) {
		final EntityNestedQueryComparator comparator = processingScope.getEntityNestedQueryComparator();
		if (comparator != null) {
			applyLocales(container, comparator, processingScope);
		}
	}

	/**
	 * Sets the locale of every `entityLocaleEquals` the visit of the index objects would translate within the
	 * processing scope of the container, in visiting order - see {@link #applyLocalesToNestedQueryComparator}.
	 *
	 * @param container       the container to walk
	 * @param comparator      the comparator of the ordering of the fetched references
	 * @param processingScope the processing scope of the rows
	 */
	private static void applyLocales(
		@Nonnull ConstraintContainer<FilterConstraint> container,
		@Nonnull EntityNestedQueryComparator comparator,
		@Nonnull ProcessingScope<?> processingScope
	) {
		for (FilterConstraint child : container) {
			if (processingScope.isSuppressed(child.getClass())) {
				continue;
			}
			if (child instanceof EntityLocaleEquals entityLocaleEquals) {
				comparator.setLocale(entityLocaleEquals.getLocale());
			} else if (
				child instanceof And || child instanceof Or || child instanceof Not ||
					child instanceof UserFilter || child instanceof FilterInScope
			) {
				//noinspection unchecked
				applyLocales((ConstraintContainer<FilterConstraint>) child, comparator, processingScope);
			}
		}
	}

	/**
	 * This FormulaPostProcessor handles important aspect of the `scope` filter when both indexed and non-indexed data
	 * are requested. Imagine you have a schema with an attribute `code` that is indexed in the live scope and not indexed
	 * in the archived scope. If you query the entities by the `code` attribute in both scopes in the following way
	 *
	 * ```evitaql
	 * query(
	 * collection("entity"),
	 * filterBy(
	 * entityPrimaryKeyInSet(1, 2, 3),
	 * inScope(LIVE, attributeIs("code", NOT_NULL)),
	 * scope(LIVE)
	 * )
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
	 * AND(                 // SCOPE(ARCHIVED)
	 * [1, 2],          // SUPER SET OF ALL ARCHIVED ENTITIES
	 * [1, 2, 3]        // CONSTANT: ENTITY_PRIMARY_KEY_IN_SET(1, 2, 3)
	 * ),
	 * AND(                  // SCOPE(LIVE)
	 * [3],              // SUPER SET OF ALL LIVE ENTITIES
	 * [1, 2, 3],        // CONSTANT: ENTITY_PRIMARY_KEY_IN_SET(1, 2, 3)
	 * [3]               // NOT_NULL("code"),
	 * )
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
	public static final class InScopeFormulaPostProcessor implements FormulaPostProcessor {
		/**
		 * The set of scopes the visitor processes when the post-processor is registered - the scopes of the query at
		 * the top level, fewer of them in a nested evaluation narrowed to the scopes of the indexes it searches.
		 * A branch is built for each of them and for no other: a branch of a scope the evaluation does not process
		 * would stand for every entity of that scope, which the searched indexes do not hold.
		 */
		private final Set<Scope> processedScopes;
		/**
		 * Supplier that provides the super set formula.
		 */
		private final Function<Scope, Formula> superSetFormulaSupplier;
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
				this.processedScopes.stream()
					.map(
						scope -> {
							// if the formula is a ScopeContainerFormula and the scope matches, return the inner formulas
							// otherwise skip the container including the inner formulas
							final Formula clonedFormula = FormulaCloner.clone(
								formula,
								examinedFormula -> {
									// if the formula is a ScopeContainerFormula and the scope matches, return the inner formulas
									// otherwise skip the container including the inner formulas
									if (examinedFormula instanceof ScopeContainerFormula scf) {
										if (scf.getScope() == scope) {
											final Formula[] innerFormulas = scf.getInnerFormulas();
											// if the inner formulas are empty, we need to use super set formula instead
											// if we'd use the AND factory, it would produce an empty formula, which is not correct
											return innerFormulas.length == 0 ?
												this.superSetFormulaSupplier.apply(scope) : FormulaFactory.and(innerFormulas);
										} else {
											// the same applies here - if the scope doesn't match, we need to provide
											// the super set formula instead
											return this.superSetFormulaSupplier.apply(scope);
										}
									} else {
										return examinedFormula;
									}
								}
							);
							// if the result formula is empty - it means that for particular scope there are no constraints set
							// we need to use super set formula instead - i.e. all entities in the scope match the "zero" constraints
							// the container stands for the whole scope once a clone removes every constraint it holds
							final Formula scopeSuperSet = this.superSetFormulaSupplier.apply(scope);
							return clonedFormula == null ?
								scopeSuperSet :
								ScopeContainerFormula.restrictingScope(scope, scopeSuperSet, clonedFormula);
						}
					)
					.toArray(Formula[]::new)
			);
		}
	}

}
