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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.filter.AttributeIs;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.UniqueIndex;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeIs} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeIsTranslator implements FilteringConstraintTranslator<AttributeIs> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeIs attributeIs, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeIs.getAttributeName();

		// also consider the possibly more special values supported in the future
		return switch (attributeIs.getAttributeSpecialValue()) {
			case NULL -> translateIsNull(attributeName, filterByVisitor);
			case NOT_NULL -> translateIsNotNull(attributeName, filterByVisitor);
		};
	}

	@Nonnull
	private static Formula translateIsNull(@Nonnull String attributeName, @Nonnull FilterByVisitor filterByVisitor) {
		if (filterByVisitor.isEntityTypeKnown()) {
			final AttributeSchemaContract attributeDefinition = filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE);
			// if attribute is unique prefer O(1) hash map lookup over inverted index
			if (attributeDefinition.isUnique()) {
				return FutureNotFormula.postProcess(
					filterByVisitor.getEntityIndexStream().map(it -> {
						final UniqueIndex uniqueIndex = it.getUniqueIndex(attributeDefinition, filterByVisitor.getLocale());
						if (uniqueIndex == null) {
							return EmptyFormula.INSTANCE;
						}
						return new NotFormula(
							uniqueIndex.getRecordIdsFormula(),
							it.getAllPrimaryKeysFormula()
						);
					}).toArray(Formula[]::new),
					formulas -> {
						if (formulas.length == 0) {
							return EmptyFormula.INSTANCE;
						} else {
							return new AttributeFormula(
								attributeDefinition.isLocalized() ?
									new AttributeKey(attributeName, filterByVisitor.getLocale()) : new AttributeKey(attributeName),
								FormulaFactory.or(formulas));
						}
					}
				);
			} else {
				return FutureNotFormula.postProcess(
					filterByVisitor.getEntityIndexStream().map(it -> {
						final FilterIndex filterIndex = it.getFilterIndex(attributeDefinition.getName(), filterByVisitor.getLocale());
						if (filterIndex == null) {
							return it.getAllPrimaryKeysFormula();
						}
						return new NotFormula(
							filterIndex.getAllRecordsFormula(),
							it.getAllPrimaryKeysFormula()
						);
					}).toArray(Formula[]::new),
					formulas -> {
						if (formulas.length == 0) {
							return EmptyFormula.INSTANCE;
						} else {
							return new AttributeFormula(
								attributeDefinition.isLocalized() ?
									new AttributeKey(attributeName, filterByVisitor.getLocale()) : new AttributeKey(attributeName),
								FormulaFactory.or(formulas)
							);
						}
					}
				);
			}
		} else {
			return new EntityFilteringFormula(
				"attribute is filter",
				filterByVisitor,
				createAlternativeNullBitmapFilter(attributeName, filterByVisitor)
			);
		}
	}

	@Nonnull
	private static Formula translateIsNotNull(@Nonnull String attributeName, @Nonnull FilterByVisitor filterByVisitor) {
		if (filterByVisitor.isEntityTypeKnown()) {
			final AttributeSchemaContract attributeDefinition = filterByVisitor.getAttributeSchema(attributeName);
			// if attribute is unique prefer O(1) hash map lookup over histogram
			if (attributeDefinition.isUnique()) {
				return new AttributeFormula(
					attributeDefinition.isLocalized() ?
						new AttributeKey(attributeName, filterByVisitor.getLocale()) : new AttributeKey(attributeName),
					filterByVisitor.applyOnUniqueIndexes(
						attributeDefinition, index -> new ConstantFormula(index.getRecordIds())
					)
				);
			} else {
				final AttributeFormula filteringFormula = new AttributeFormula(
					attributeDefinition.isLocalized() ?
						new AttributeKey(attributeName, filterByVisitor.getLocale()) : new AttributeKey(attributeName),
					filterByVisitor.applyOnFilterIndexes(
						attributeDefinition, FilterIndex::getAllRecordsFormula
					)
				);
				if (filterByVisitor.isPrefetchPossible()) {
					return new SelectionFormula(
						filterByVisitor,
						filteringFormula,
						createAlternativeNotNullBitmapFilter(attributeName, filterByVisitor)
					);
				} else {
					return filteringFormula;
				}
			}
		} else {
			return new EntityFilteringFormula(
				"attribute is filter",
				filterByVisitor,
				createAlternativeNotNullBitmapFilter(attributeName, filterByVisitor)
			);
		}
	}

	@Nonnull
	private static AttributeBitmapFilter createAlternativeNullBitmapFilter(@Nonnull String attributeName, @Nonnull FilterByVisitor filterByVisitor) {
		final ProcessingScope processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			processingScope.getRequirements(),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> optionalStream -> optionalStream.noneMatch(Optional::isPresent),
			AttributeTrait.FILTERABLE
		);
	}

	@Nonnull
	private static AttributeBitmapFilter createAlternativeNotNullBitmapFilter(@Nonnull String attributeName, @Nonnull FilterByVisitor filterByVisitor) {
		final ProcessingScope processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			processingScope.getRequirements(),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> optionalStream -> optionalStream.anyMatch(Optional::isPresent),
			AttributeTrait.FILTERABLE
		);
	}

}
