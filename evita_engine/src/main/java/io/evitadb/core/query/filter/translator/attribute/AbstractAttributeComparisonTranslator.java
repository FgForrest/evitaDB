/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import io.evitadb.api.query.filter.AbstractAttributeFilterComparisonConstraintLeaf;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeValueMatcher;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.index.Index;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * An abstract class designed to translate attribute comparison constraints into specific filtering formulas.
 * Extends functionality to handle attribute-based filtering with various predicates to compare results.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class AbstractAttributeComparisonTranslator extends AbstractAttributeTranslator {
	/**
	 * The predicate used to compare the result of the filtering operation.
	 */
	private final IntPredicate comparisonResultPredicate;
	/**
	 * The function that will extract formula matching the searched string from the filter index.
	 */
	private final BiFunction<FilterIndex, Serializable, Formula> filterIndexResolver;
	/**
	 * The description of the attribute comparison filter.
	 */
	private final String description;

	/**
	 * Creates the matcher of the comparison - the test of a single attribute value the operator of this translator
	 * stands for.
	 *
	 * @param attributeConstraint the comparison constraint
	 * @param attributeSchema     the schema of the attribute the constraint is applied to
	 * @param attributeIndexKey   key of the filter index the attribute is read from, its locale selects the comparator
	 * @return the matcher
	 */
	@Nonnull
	protected AttributeValueMatcher createComparisonMatcher(
		@Nonnull AbstractAttributeFilterComparisonConstraintLeaf attributeConstraint,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		return AttributeValueMatcher.comparing(
			attributeIndexKey, attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces(),
			attributeConstraint.getAttributeValue(), this.comparisonResultPredicate
		);
	}

	/**
	 * Translates the provided attribute constraint into a filtering formula: the one the filter index answers, wrapped
	 * in a `SelectionFormula` with a prefetch alternative when the entities can be prefetched, or an
	 * `EntityFilteringFormula` for a query with no collection.
	 *
	 * The matcher of the constraint is created before any index is read, so a decimal value the attribute cannot
	 * index is refused whatever the index stores.
	 *
	 * @param attributeConstraint The attribute filter constraint that contains the attribute name and value to be filtered.
	 * @param filterByVisitor The visitor used to traverse and process filter constraints.
	 * @param translator the translator of the constraint, which creates the matcher the prefetched entities are
	 *                   tested with
	 * @return The resulting formula based on the attribute constraint and the visitor's filtering logic.
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when a decimal value does not fit the index key
	 */
	@Nonnull
	protected <T extends AbstractAttributeFilterComparisonConstraintLeaf> Formula translateInternal(
		@Nonnull T attributeConstraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeValueMatchingTranslator<T> translator
	) {
		final String attributeName = attributeConstraint.getAttributeName();
		final Serializable attributeValue = attributeConstraint.getAttributeValue();
		final Optional<GlobalAttributeSchemaContract> optionalGlobalAttributeSchema = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName, AttributeTrait.FILTERABLE);

		if (filterByVisitor.isEntityTypeKnown() || optionalGlobalAttributeSchema.isPresent()) {
			final AttributeSchemaContract attributeSchema = optionalGlobalAttributeSchema
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			// created before any index is read, so a value the attribute cannot hold is refused whatever it stores
			final AttributeValueMatcher matcher = translator.createMainQueryMatcher(
				attributeConstraint, filterByVisitor, attributeSchema
			);
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeSchema);

			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			final Class<? extends Serializable> attributeType = attributeSchema.getPlainType();
			final Serializable comparableValue = EvitaDataTypes.toTargetType(attributeValue, attributeType);
			final AttributeFormula filteringFormula = new AttributeFormula(
				attributeSchema instanceof GlobalAttributeSchemaContract,
				attributeKey,
				filterByVisitor.applyOnFilterIndexes(
					processingScope.getReferenceSchema(),
					attributeSchema,
					index -> this.filterIndexResolver.apply(index, comparableValue)
				),
				createHistogramRequestedPredicate(attributeType, Objects.requireNonNull(comparableValue), this.comparisonResultPredicate)
			);
			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filteringFormula,
					translator.createAlternativeBitmapFilter(attributeConstraint, filterByVisitor, matcher)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute " + this.description + " filter",
				translator.createEntityFilteringBitmapFilter(attributeConstraint, filterByVisitor)
			);
		}

	}

	/**
	 * Creates a predicate based on the provided attribute type and comparable value for histogram requests.
	 * The predicate is used to determine {@link Bucket#requested()} property.
	 *
	 * @param attributeType The type of the attribute to compare.
	 * @param comparableValue The value to compare the attribute against.
	 * @return A predicate to apply on BigDecimal values, or null if the attribute type is not a Number.
	 */
	@Nullable
	private static Predicate<BigDecimal> createHistogramRequestedPredicate(
		@Nonnull Class<? extends Serializable> attributeType,
		@Nonnull Serializable comparableValue,
		@Nonnull IntPredicate comparisonPredicate
	) {
		final Predicate<BigDecimal> requestedPredicate;
		if (Number.class.isAssignableFrom(attributeType)) {
			final BigDecimal fromBigDecimal = EvitaDataTypes.toTargetType(comparableValue, BigDecimal.class);
			requestedPredicate = threshold -> fromBigDecimal == null || comparisonPredicate.test(threshold.compareTo(fromBigDecimal));
		} else {
			requestedPredicate = null;
		}
		return requestedPredicate;
	}

}
