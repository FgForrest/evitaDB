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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.index.Index;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContent;

/**
 * An abstract class designed to translate attribute comparison constraints into specific filtering formulas.
 * Extends functionality to handle attribute-based filtering with various predicates to compare results.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
class AbstractAttributeComparisonTranslator extends AbstractAttributeTranslator {
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
	 * Translates the provided attribute constraint into a filtering formula.
	 * This method processes the attribute constraint using the visitor to
	 * apply the appropriate filter based on the attribute name and value.
	 *
	 * @param attributeConstraint The attribute filter constraint that contains the attribute name and value to be filtered.
	 * @param filterByVisitor The visitor used to traverse and process filter constraints.
	 * @return The resulting formula based on the attribute constraint and the visitor's filtering logic.
	 */
	@Nonnull
	protected Formula translateInternal(@Nonnull AbstractAttributeFilterComparisonConstraintLeaf attributeConstraint, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeConstraint.getAttributeName();
		final Serializable attributeValue = attributeConstraint.getAttributeValue();
		final Optional<GlobalAttributeSchemaContract> optionalGlobalAttributeSchema = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName, AttributeTrait.FILTERABLE);

		if (filterByVisitor.isEntityTypeKnown() || optionalGlobalAttributeSchema.isPresent()) {
			final AttributeSchemaContract attributeSchema = optionalGlobalAttributeSchema
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
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
					createAlternativeBitmapFilter(filterByVisitor, attributeName, attributeValue, this.comparisonResultPredicate)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute " + this.description + " filter",
				createAlternativeBitmapFilter(filterByVisitor, attributeName, attributeValue, this.comparisonResultPredicate)
			);
		}

	}

	/**
	 * Creates an {@link AttributeBitmapFilter} for the provided attribute key and value. The filter is used to filter
	 * prefetched entities based on the attribute key and value.
	 *
	 * @param filterByVisitor The visitor used to traverse and process filters.
	 * @param attributeName The name of the attribute to be used in the filter.
	 * @param attributeValue The value of the attribute to be used in the filter.
	 * @return An instance of {@link AttributeBitmapFilter} configured with the given attribute key and value.
	 */
	@Nonnull
	static AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String attributeName,
		@Nonnull Serializable attributeValue,
		@Nonnull IntPredicate comparisonResultPredicate
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			attributeContent(attributeName),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> {
				final Function<Serializable, Predicate<Stream<Optional<AttributeValue>>>> predicateFactory = valueToCompare -> getPredicate(
					processingScope.getReferenceSchema(),
					attributeSchema,
					filterByVisitor.getLocale(),
					valueToCompare,
					comparisonResultPredicate
				);

				if (attributeValue.getClass().isArray()) {
					final Serializable[] valueItems = (Serializable[]) attributeValue;
					Predicate<Stream<Optional<AttributeValue>>> thePredicate = null;
					for (Serializable valueItem : valueItems) {
						final Serializable comparableValue = EvitaDataTypes.toTargetType(valueItem, attributeSchema.getPlainType());
						thePredicate = thePredicate == null ?
							predicateFactory.apply(comparableValue) : thePredicate.or(predicateFactory.apply(comparableValue));
					}
					return thePredicate;
				} else {
					final Serializable comparableValue = EvitaDataTypes.toTargetType(attributeValue, attributeSchema.getPlainType());
					return predicateFactory.apply(comparableValue);
				}
			}
		);
	}

	/**
	 * Generates a predicate to evaluate whether a stream of attribute values matches a given schema, key, and comparable value.
	 *
	 * @param referenceSchema the schema of the reference containing attribute schema,
	 *                        may be null if attribute is defined on entity level
	 * @param attributeSchema the schema of the attribute containing type details
	 * @param locale          the request locale, may be null if no locale was specified
	 * @param comparableValue the value to compare against attribute values
	 * @return a predicate for evaluating whether the stream contains matching attribute values
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	static Predicate<Stream<Optional<AttributeValue>>> getPredicate(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale,
		@Nullable Serializable comparableValue,
		@Nonnull IntPredicate comparisonPredicate
	) {
		final Comparator comparator = FilterIndex.getComparator(
			AttributeIndex.createAttributeKey(referenceSchema, attributeSchema, locale),
			attributeSchema.getPlainType()
		);
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(attributeSchema.getPlainType());
		final Serializable comparedValue = normalizer.apply(comparableValue);
		final Predicate<Comparable> predicate = theValue -> theValue != null && comparisonPredicate.test(comparator.compare(theValue, comparedValue));

		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Serializable theValue = Objects.requireNonNull(attr.get().value());
					if (theValue.getClass().isArray()) {
						return Arrays.stream((Object[])theValue).map(normalizer).map(Comparable.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((Comparable)normalizer.apply(theValue));
					}
				}
			}
		);
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
