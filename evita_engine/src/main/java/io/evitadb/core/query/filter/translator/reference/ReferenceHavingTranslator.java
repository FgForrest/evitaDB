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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.ReferenceNotFoundException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.EntityIndex;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link EntityPrimaryKeyInSet} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceHavingTranslator implements FilteringConstraintTranslator<ReferenceHaving>, SelfTraversingTranslator {
	private static final Formula[] EMPTY_FORMULA = new Formula[0];

	@Nonnull
	@Override
	public Formula translate(@Nonnull ReferenceHaving referenceHaving, @Nonnull FilterByVisitor filterByVisitor) {
		final String referenceName = referenceHaving.getReferenceName();
		final EntitySchemaContract entitySchema = filterByVisitor.getSchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));
		isTrue(referenceSchema.isFilterable(), () -> new ReferenceNotIndexedException(referenceName, entitySchema));

		final List<EntityIndex> referencedEntityIndexes = getTargetIndexes(
			filterByVisitor, referenceHaving
		);
		if (referencedEntityIndexes.isEmpty()) {
			return EmptyFormula.INSTANCE;
		} else {
			return applySearchOnIndexes(referenceHaving, filterByVisitor, referenceSchema, referencedEntityIndexes);
		}
	}

	private Formula applySearchOnIndexes(
		@Nonnull ReferenceHaving filterConstraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull List<EntityIndex> referencedEntityIndexes
	) {
		final List<Formula> referencedEntityFormulas = new ArrayList<>(referencedEntityIndexes.size());
		for (EntityIndex referencedEntityIndex : referencedEntityIndexes) {
			final ProcessingScope processingScope = filterByVisitor.getProcessingScope();
			referencedEntityFormulas.add(
				filterByVisitor.executeInContext(
					Collections.singletonList(referencedEntityIndex),
					ReferenceContent.ALL_REFERENCES,
					referenceSchema,
					processingScope.getNestedQueryFormulaEnricher(),
					processingScope.getEntityNestedQueryComparator(),
					(theEntitySchema, attributeName) -> FilterByVisitor.getReferenceAttributeSchema(
						attributeName, ofNullable(theEntitySchema).orElseGet(filterByVisitor::getSchema), referenceSchema
					),
					(entityContract, attributeName, locale) -> entityContract.getReferences(referenceSchema.getName()).stream().map(it -> it.getAttributeValue(attributeName, locale)),
					() -> {
						getFilterByFormula(filterConstraint).ifPresent(it -> it.accept(filterByVisitor));
						final Formula[] collectedFormulas = filterByVisitor.getCollectedFormulasOnCurrentLevel();
						return switch (collectedFormulas.length) {
							case 0 -> filterByVisitor.getSuperSetFormula();
							case 1 -> collectedFormulas[0];
							default -> new OrFormula(collectedFormulas);
						};
					},
					EntityPrimaryKeyInSet.class
				)
			);
		}
		return FormulaFactory.or(
			referencedEntityFormulas.toArray(EMPTY_FORMULA)
		);
	}

	@Nonnull
	private static Optional<FilterConstraint> getFilterByFormula(@Nonnull ReferenceHaving filterConstraint) {
		final FilterConstraint[] children = filterConstraint.getChildren();
		if (children.length == 0) {
			return empty();
		} else if (children.length == 1) {
			return of(children[0]);
		} else {
			return of(new And(children));
		}
	}

	@Nonnull
	private List<EntityIndex> getTargetIndexes(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull ReferenceHaving referenceHaving
	) {
		final TargetIndexes targetIndexes = filterByVisitor.findTargetIndexSet(referenceHaving);
		final List<EntityIndex> referencedEntityIndexes;
		if (targetIndexes == null) {
			referencedEntityIndexes = filterByVisitor.getReferencedRecordEntityIndexes(referenceHaving);
		} else {
			referencedEntityIndexes = targetIndexes.getIndexesOfType(EntityIndex.class);
		}
		return referencedEntityIndexes;
	}

}
