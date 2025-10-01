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

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.filter.AttributeIs;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
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
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeIs} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeIsTranslator extends AbstractAttributeTranslator
	implements FilteringConstraintTranslator<AttributeIs> {

	/**
	 * Translates an "IS NULL" attribute condition into a corresponding Formula.
	 *
	 * @param attributeName   the name of the attribute to be checked for null values
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return a Formula representing the translated "IS NULL" condition for the specified attribute
	 */
	@Nonnull
	private static Formula translateIsNull(
		@Nonnull String attributeName,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		if (filterByVisitor.isEntityTypeKnown()) {
			final Set<Scope> scopes = filterByVisitor.getProcessingScope().getScopes();
			final AttributeSchemaContract attributeDefinition = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName, AttributeTrait.FILTERABLE)
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeDefinition);

			// if attribute is unique prefer O(1) hash map lookup over inverted index
			if (attributeDefinition instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
				scopes.stream().anyMatch(globalAttributeSchema::isUniqueGloballyInScope)
			) {
				return FutureNotFormula.postProcess(
					createNullGloballyUniqueSubtractionFormula(globalAttributeSchema, filterByVisitor),
					formulas -> aggregateFormulas(attributeDefinition, attributeKey, formulas)
				);
			} else if (scopes.stream().anyMatch(attributeDefinition::isUniqueInScope)) {
				return FutureNotFormula.postProcess(
					createNullUniqueSubtractionFormula(attributeDefinition, filterByVisitor),
					formulas -> aggregateFormulas(attributeDefinition, attributeKey, formulas)
				);
			} else {
				return FutureNotFormula.postProcess(
					createNullFilterableSubtractionFormula(attributeDefinition, filterByVisitor),
					formulas -> aggregateFormulas(attributeDefinition, attributeKey, formulas)
				);
			}
		} else {
			return new EntityFilteringFormula(
				"attribute is filter",
				createAlternativeNullBitmapFilter(attributeName, filterByVisitor)
			);
		}
	}

	/**
	 * Creates an array of Formulas for filtering entities where a specified attribute is null based on information
	 * in filter indexes. The formulas apply a subtraction operation to filter out records with non-null attributes.
	 *
	 * @param attributeDefinition the schema definition of the attribute being processed
	 * @param filterByVisitor     the visitor responsible for filtering operations
	 * @return an array of Formulas representing the null filterable subtraction conditions
	 */
	@Nonnull
	private static Formula[] createNullFilterableSubtractionFormula(
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		return filterByVisitor.getEntityIndexStream()
			.map(
				it -> {
					final FilterIndex filterIndex = it.getFilterIndex(attributeDefinition.getName(), filterByVisitor.getLocale());
					if (filterIndex == null) {
						return it.getAllPrimaryKeysFormula();
					}
					return new NotFormula(
						filterIndex.getAllRecordsFormula(),
						it.getAllPrimaryKeysFormula()
					);
				}
			)
			.toArray(Formula[]::new);
	}

	/**
	 * Creates an array of Formulas for filtering entities where a specified attribute is null based on information
	 * in unique indexes. The formulas apply a subtraction operation to filter out records with non-null attributes.
	 *
	 * @param attributeDefinition the schema definition of the attribute being processed
	 * @param filterByVisitor     the visitor responsible for filtering operations
	 * @return an array of Formulas representing the null filterable subtraction conditions
	 */
	@Nonnull
	private static Formula[] createNullGloballyUniqueSubtractionFormula(
		@Nonnull GlobalAttributeSchemaContract attributeDefinition,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		return new Formula[]{
			filterByVisitor.applyOnGlobalUniqueIndexes(
				attributeDefinition,
				uniqueIndex -> new NotFormula(
					uniqueIndex.getRecordIdsFormula(filterByVisitor.getEntityType()),
					FormulaFactory.or(
						filterByVisitor.getEntityIndexStream()
							.map(EntityIndex::getAllPrimaryKeysFormula)
							.toArray(Formula[]::new)
					)
				)
			)
		};
	}

	/**
	 * Creates an array of Formulas for filtering entities where a specified attribute is null based on information
	 * in unique indexes. The formulas apply a subtraction operation to filter out records with non-null attributes.
	 *
	 * @param attributeDefinition the schema definition of the attribute being processed
	 * @param filterByVisitor     the visitor responsible for filtering operations
	 * @return an array of Formulas representing the null filterable subtraction conditions
	 */
	@Nonnull
	private static Formula[] createNullUniqueSubtractionFormula(
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		return filterByVisitor.getEntityIndexStream()
			.map(
				it -> {
					final UniqueIndex uniqueIndex = it.getUniqueIndex(attributeDefinition, filterByVisitor.getLocale());
					return uniqueIndex == null ?
						EmptyFormula.INSTANCE :
						new NotFormula(
							uniqueIndex.getRecordIdsFormula(),
							it.getAllPrimaryKeysFormula()
						);
				}
			)
			.toArray(Formula[]::new);
	}

	/**
	 * Aggregates the provided formulas into a single Formula (either empty formula or disjunctive join).
	 *
	 * @param attributeDefinition the schema definition of the attribute being processed
	 * @param attributeKey        the key of the attribute being processed
	 * @param formulas            an array of formulas to be aggregated
	 * @return an AbstractFormula that represents the aggregation of the input formulas
	 */
	@Nonnull
	private static Formula aggregateFormulas(
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Formula[] formulas
	) {
		if (formulas.length == 0) {
			return EmptyFormula.INSTANCE;
		} else {
			return new AttributeFormula(
				attributeDefinition instanceof GlobalAttributeSchemaContract,
				attributeKey,
				FormulaFactory.or(formulas)
			);
		}
	}

	/**
	 * Translates an "IS NOT NULL" attribute condition into a corresponding Formula.
	 *
	 * @param attributeName   the name of the attribute to be checked for non-null values
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return a Formula representing the translated "IS NOT NULL" condition for the specified attribute
	 */
	@Nonnull
	private static Formula translateIsNotNull(
		@Nonnull String attributeName,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		if (filterByVisitor.isEntityTypeKnown()) {
			final Set<Scope> scopes = filterByVisitor.getProcessingScope().getScopes();
			final AttributeSchemaContract attributeDefinition = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName, AttributeTrait.FILTERABLE)
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeDefinition);
			// if attribute is unique prefer O(1) hash map lookup over histogram
			if (attributeDefinition instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
				scopes.stream().anyMatch(globalAttributeSchema::isUniqueGloballyInScope)
			) {
				return new AttributeFormula(
					true,
					attributeKey,
					filterByVisitor.applyOnGlobalUniqueIndexes(
						globalAttributeSchema,
						index -> {
							final Bitmap recordIds = index.getRecordIds(filterByVisitor.getEntityType());
							return recordIds.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(recordIds);
						}
					)
				);
			} else if (scopes.stream().anyMatch(attributeDefinition::isUniqueInScope)) {
				return new AttributeFormula(
					attributeDefinition instanceof GlobalAttributeSchemaContract,
					attributeKey,
					filterByVisitor.applyOnUniqueIndexes(
						attributeDefinition,
						index -> {
							final Bitmap recordIds = index.getRecordIds();
							return recordIds.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(recordIds);
						}
					)
				);
			} else {
				final AttributeFormula filteringFormula = new AttributeFormula(
					attributeDefinition instanceof GlobalAttributeSchemaContract,
					attributeKey,
					filterByVisitor.applyOnFilterIndexes(
						attributeDefinition,
						FilterIndex::getAllRecordsFormula
					)
				);
				if (filterByVisitor.isPrefetchPossible()) {
					return new SelectionFormula(
						filteringFormula,
						createAlternativeNotNullBitmapFilter(attributeKey.attributeName(), filterByVisitor)
					);
				} else {
					return filteringFormula;
				}
			}
		} else {
			return new EntityFilteringFormula(
				"attribute is filter",
				createAlternativeNotNullBitmapFilter(attributeName, filterByVisitor)
			);
		}
	}

	/**
	 * Creates an AttributeBitmapFilter that checks for the absence of a specified attribute.
	 *
	 * @param attributeName   the name of the attribute to be checked for absence
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return an AttributeBitmapFilter instance configured to check for attribute absence
	 */
	@Nonnull
	private static AttributeBitmapFilter createAlternativeNullBitmapFilter(
		@Nonnull String attributeName,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			Objects.requireNonNull(processingScope.getRequirements()),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> optionalStream -> optionalStream.noneMatch(Optional::isPresent),
			AttributeTrait.FILTERABLE
		);
	}

	/**
	 * Creates an AttributeBitmapFilter that ensures a specified attribute is not null.
	 *
	 * @param attributeName   the name of the attribute to check for non-null values
	 * @param filterByVisitor the visitor responsible for filtering operations
	 * @return an AttributeBitmapFilter instance configured to check for non-null attribute values
	 */
	@Nonnull
	private static AttributeBitmapFilter createAlternativeNotNullBitmapFilter(
		@Nonnull String attributeName,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			Objects.requireNonNull(processingScope.getRequirements()),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> optionalStream -> optionalStream.anyMatch(Optional::isPresent),
			AttributeTrait.FILTERABLE
		);
	}

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

}
