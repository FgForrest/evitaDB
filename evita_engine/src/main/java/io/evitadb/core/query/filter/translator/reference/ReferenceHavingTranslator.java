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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.ReducedEntityIndex;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link EntityPrimaryKeyInSet} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceHavingTranslator implements FilteringConstraintTranslator<ReferenceHaving>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Formula translate(@Nonnull ReferenceHaving referenceHaving, @Nonnull FilterByVisitor filterByVisitor) {
		final String referenceName = referenceHaving.getReferenceName();
		final EntitySchemaContract entitySchema = filterByVisitor.getProcessingScope().getEntitySchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));
		isTrue(referenceSchema.isIndexed(), () -> new ReferenceNotIndexedException(referenceName, entitySchema));

		final Supplier<List<ReducedEntityIndex>> referencedEntityIndexesSupplier = () -> getTargetIndexes(
			filterByVisitor, referenceHaving
		);

		return applySearchOnIndexes(
			referenceHaving, filterByVisitor, entitySchema, referenceSchema, referencedEntityIndexesSupplier
		);
	}

	@Nonnull
	private static Formula applySearchOnIndexes(
		@Nonnull ReferenceHaving filterConstraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Supplier<List<ReducedEntityIndex>> referencedEntityIndexSupplier
	) {
		final String referenceName = referenceSchema.getName();
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return filterByVisitor.executeInContextAndIsolatedFormulaStack(
			ReducedEntityIndex.class,
			referencedEntityIndexSupplier,
			ReferenceContent.ALL_REFERENCES,
			entitySchema,
			referenceSchema,
			processingScope.getNestedQueryFormulaEnricher(),
			processingScope.getEntityNestedQueryComparator(),
			processingScope.withReferenceSchemaAccessor(referenceName),
			(entityContract, attributeName, locale) -> entityContract.getReferences(referenceName)
				.stream()
				.map(it -> it.getAttributeValue(attributeName, locale)),
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
	private static List<ReducedEntityIndex> getTargetIndexes(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull ReferenceHaving referenceHaving
	) {
		final TargetIndexes<?> targetIndexes = filterByVisitor.findTargetIndexSet(referenceHaving);
		final List<ReducedEntityIndex> referencedEntityIndexes;
		if (targetIndexes == null) {
			referencedEntityIndexes = filterByVisitor.getReferencedRecordEntityIndexes(referenceHaving);
		} else {
			//noinspection unchecked
			referencedEntityIndexes = (List<ReducedEntityIndex>) targetIndexes.getIndexes();
		}
		return referencedEntityIndexes;
	}

}
