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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryPlanner.EnclosingContainerRelation;
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
import io.evitadb.index.Index;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			final Set<Scope> scopes = processingScope.getScopes();
			final AttributeSchemaContract attributeSchema = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName, AttributeTrait.FILTERABLE)
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final ReferenceSchemaContract referenceSchema = processingScope.getReferenceSchema();
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeSchema);

			// if attribute is unique prefer O(1) hash map lookup over inverted index
			if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
				scopes.stream().anyMatch(globalAttributeSchema::isUniqueGloballyInScope)
			) {
				return wrapFormula(
					attributeSchema,
					attributeKey,
					FutureNotFormula.postProcess(
						createNullGloballyUniqueSubtractionFormula(globalAttributeSchema, filterByVisitor),
						EnclosingContainerRelation.DISJUNCTION
					)
				);
			} else if (scopes.stream().anyMatch(attributeSchema::isUniqueInScope)) {
				return wrapFormula(
					attributeSchema,
					attributeKey,
					FutureNotFormula.postProcess(
						createNullUniqueSubtractionFormula(referenceSchema, attributeSchema, filterByVisitor),
						EnclosingContainerRelation.DISJUNCTION
					)
				);
			} else {
				return wrapFormula(
					attributeSchema,
					attributeKey,
					FutureNotFormula.postProcess(
						createNullFilterableSubtractionFormula(referenceSchema, attributeSchema, filterByVisitor),
						EnclosingContainerRelation.DISJUNCTION
					)
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
	 * @param attributeSchema the schema definition of the attribute being processed
	 * @param filterByVisitor     the visitor responsible for filtering operations
	 * @return an array of Formulas representing the null filterable subtraction conditions
	 */
	@Nonnull
	private static Formula[] createNullFilterableSubtractionFormula(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		// this runs once per entity index in scope, which for a reference filter is once per referenced entity of
		// the whole collection - an allocation-light loop, not a stream
		final List<Formula> subtractions = new ArrayList<>(64);
		final Locale locale = attributeSchema.isLocalized() ? filterByVisitor.getLocale() : null;
		filterByVisitor.getEntityIndexStream().forEach(
			it -> {
				final FilterIndex filterIndex = it.getFilterIndex(referenceSchema, attributeSchema, locale);
				if (filterIndex == null) {
					// nothing in this index carries the attribute, so every record in it is null
					final Formula allPrimaryKeys = it.getAllPrimaryKeysFormula();
					if (!(allPrimaryKeys instanceof EmptyFormula)) {
						subtractions.add(allPrimaryKeys);
					}
				} else {
					final Formula subtracted = filterIndex.getAllRecordsFormula();
					final Formula superSet = it.getAllPrimaryKeysFormula();
					if (subtractionMayYieldRecords(subtracted, superSet)) {
						subtractions.add(new NotFormula(subtracted, superSet));
					}
				}
			}
		);
		return subtractions.toArray(Formula.EMPTY_FORMULA_ARRAY);
	}

	/**
	 * Tells whether `superSet \ subtracted` may still yield records, and therefore whether building a
	 * {@link NotFormula} for it is worth the nodes it costs.
	 *
	 * Answers conservatively, and the asymmetry is deliberate: FALSE is returned only when emptiness is *certain*,
	 * so anything this method cannot settle cheaply is reported as "may yield records" and left to the execution
	 * phase, which computes the real difference. A wrong FALSE would silently drop records from the answer; a
	 * wrong TRUE costs only the nodes it was trying to save.
	 *
	 * An index whose every record carries a value for the attribute contributes nothing to an `attributeIs(NULL)`
	 * disjunction, yet it still costs a {@link NotFormula} and its two operands in the tree - and the enclosing
	 * disjunction is built by the non-folding one-argument {@link io.evitadb.core.query.algebra.utils.FormulaFactory}
	 * `or`, so those nodes survive planning, hashing, cost estimation and every post-processor walk. Dropping them
	 * here is what lets an all-empty disjunction collapse to {@link EmptyFormula} instead.
	 *
	 * The test is the exact subset relation rather than a cardinality comparison: equal sizes would only imply an
	 * empty difference under the assumption that the filter index never holds a record the entity index does not,
	 * and this code does not need to rest on that. {@link PersistentRoaringBitmap#contains(PersistentRoaringBitmap)}
	 * walks containers with an early exit, so the check is cheap and allocation-free.
	 *
	 * @param subtracted the records that do carry a value
	 * @param superSet   all records tracked by the index
	 * @return FALSE only when the difference is certainly empty, TRUE whenever it may hold records
	 */
	private static boolean subtractionMayYieldRecords(@Nonnull Formula subtracted, @Nonnull Formula superSet) {
		if (superSet instanceof EmptyFormula) {
			// nothing is tracked here, so nothing can remain after the subtraction
			return false;
		}
		if (!(subtracted instanceof ConstantFormula subtractedConstant) ||
			!(superSet instanceof ConstantFormula superSetConstant)
		) {
			// the operands are not plain bitmaps - leave the subtraction to the execution phase
			return true;
		}
		// `contains` answers TRUE when every tracked record carries a value - the difference is then empty,
		// which is precisely the case this method reports as FALSE, hence the negation
		return !RoaringBitmapBackedBitmap.getRoaringBitmap(subtractedConstant.getDelegate())
			.contains(RoaringBitmapBackedBitmap.getRoaringBitmap(superSetConstant.getDelegate()));
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
					uniqueIndex.getRecordIdsFormula(filterByVisitor.getEntityType(), filterByVisitor.getEntityTypeClassifierResolver()),
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
	 * @param attributeSchema the schema definition of the attribute being processed
	 * @param filterByVisitor     the visitor responsible for filtering operations
	 * @return an array of Formulas representing the null filterable subtraction conditions
	 */
	@Nonnull
	private static Formula[] createNullUniqueSubtractionFormula(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final List<Formula> subtractions = new ArrayList<>(64);
		filterByVisitor.getEntityIndexStream().forEach(
			it -> {
				final UniqueIndex uniqueIndex = it.getUniqueIndex(
					referenceSchema, attributeSchema, filterByVisitor.getLocale()
				);
				if (uniqueIndex != null) {
					final Formula subtracted = uniqueIndex.getRecordIdsFormula();
					final Formula superSet = it.getAllPrimaryKeysFormula();
					if (subtractionMayYieldRecords(subtracted, superSet)) {
						subtractions.add(new NotFormula(subtracted, superSet));
					}
				}
			}
		);
		return subtractions.toArray(Formula.EMPTY_FORMULA_ARRAY);
	}

	/**
	 * Aggregates the provided formulas into a single Formula (either empty formula or disjunctive join).
	 *
	 * @param attributeDefinition the schema definition of the attribute being processed
	 * @param attributeKey        the key of the attribute being processed
	 * @param formula            an array of formulas to be aggregated
	 * @return an AbstractFormula that represents the aggregation of the input formulas
	 */
	@Nonnull
	private static Formula wrapFormula(
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Formula formula
	) {
		if (formula instanceof EmptyFormula) {
			return formula;
		} else {
			return new AttributeFormula(
				attributeDefinition instanceof GlobalAttributeSchemaContract,
				attributeKey,
				formula
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
			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			final Set<Scope> scopes = processingScope.getScopes();
			final AttributeSchemaContract attributeSchema = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName, AttributeTrait.FILTERABLE)
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeSchema);
			// if attribute is unique prefer O(1) hash map lookup over histogram
			if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
				scopes.stream().anyMatch(globalAttributeSchema::isUniqueGloballyInScope)
			) {
				return new AttributeFormula(
					true,
					attributeKey,
					filterByVisitor.applyOnGlobalUniqueIndexes(
						globalAttributeSchema,
						index -> {
							final Bitmap recordIds = index.getRecordIds(filterByVisitor.getEntityType(), filterByVisitor.getEntityTypeClassifierResolver());
							return recordIds.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(recordIds);
						}
					)
				);
			} else if (scopes.stream().anyMatch(attributeSchema::isUniqueInScope)) {
				return new AttributeFormula(
					attributeSchema instanceof GlobalAttributeSchemaContract,
					attributeKey,
					filterByVisitor.applyOnUniqueIndexes(
						processingScope.getReferenceSchema(),
						attributeSchema,
						index -> {
							final Bitmap recordIds = index.getRecordIds();
							return recordIds.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(recordIds);
						}
					)
				);
			} else {
				final AttributeFormula filteringFormula = new AttributeFormula(
					attributeSchema instanceof GlobalAttributeSchemaContract,
					attributeKey,
					filterByVisitor.applyOnFilterIndexes(
						processingScope.getReferenceSchema(),
						attributeSchema,
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
