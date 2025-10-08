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


import io.evitadb.api.query.filter.AbstractAttributeFilterStringSearchConstraintLeaf;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;
import io.evitadb.index.Index;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContent;

/**
 * AbstractAttributeStringSearchTranslator is an abstract class that extends the AbstractAttributeTranslator.
 * It provides methods to generate filtering formulas based on string attribute searches, applying specific
 * predicates for filtering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
class AbstractAttributeStringSearchTranslator extends AbstractAttributeTranslator {
	/**
	 * The description of the filter.
	 */
	private final String description;
	/**
	 * The function that will extract formula matching the searched string from the filter index.
	 */
	private final BiFunction<FilterIndex, String, Formula> filterIndexResolver;
	/**
	 * The predicate to test each attribute string value.
	 */
	private final BiPredicate<String, String> stringPredicate;

	/**
	 * Asserts that the provided attribute definition is of type String.
	 * This method ensures that the attribute constraint is applied only
	 * to attributes with a String type.
	 *
	 * @param attributeConstraint the attribute filter string search constraint
	 *                            that requires the attribute to be of type String
	 * @param attributeDefinition the schema definition of the attribute being validated
	 */
	static void assertStringType(
		@Nonnull AbstractAttributeFilterStringSearchConstraintLeaf attributeConstraint,
		@Nonnull AttributeSchemaContract attributeDefinition
	) {
		Assert.isTrue(
			String.class.equals(attributeDefinition.getPlainType()),
			() -> attributeConstraint.getClass().getSimpleName() + " constraint can be used only on String attributes - `" + attributeDefinition.getName() + "` is `" + attributeDefinition.getType() + "`!"
		);
	}

	/**
	 * Transforms a {@link Predicate} of type {@link String} to a {@link Predicate} of type {@link Stream}<{@link Optional}<{@link AttributeValue}>>.
	 * The transformation involves inspecting the stream of attribute values, checking if each attribute
	 * meets the given string predicate.
	 *
	 * @param predicate the predicate to test each attribute string value.
	 * @return a predicate applied to a stream of optional attribute values.
	 */
	@Nonnull
	static Predicate<Stream<Optional<AttributeValue>>> transformPredicate(@Nonnull Predicate<String> predicate) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Serializable theValue = Objects.requireNonNull(attr.get().value());
					if (theValue.getClass().isArray()) {
						return Arrays.stream((Object[]) theValue).map(String.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((String) theValue);
					}
				}
			}
		);
	}

	/**
	 * Creates an alternative bitmap filter for processing attribute conditions.
	 *
	 * @param filterByVisitor     the visitor handling the filter-by processing context
	 * @param attributeConstraint the attribute filter string search constraint leaf that provides the attribute name and the text to search for
	 * @param attributeName       the name of the attribute to filter by
	 * @param predicate           the predicate to test each attribute string value
	 * @return an instance of AttributeBitmapFilter configured with the specified parameters
	 */
	@Nonnull
	private static AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AbstractAttributeFilterStringSearchConstraintLeaf attributeConstraint,
		@Nonnull String attributeName,
		@Nonnull Predicate<String> predicate
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			attributeContent(attributeName),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> {
				assertStringType(attributeConstraint, attributeSchema);
				return transformPredicate(predicate);
			}
		);
	}

	/**
	 * Translates the given attribute string search constraint into a filtering formula suitable for application on a filter index.
	 * This method is intended for internal use to assist the filtering process by generating an appropriate formula based on the given
	 * constraints and the current state of the filter visitor.
	 *
	 * @param attributeConstraint the attribute filter string search constraint leaf that provides the attribute name and the text to search for
	 * @param filterByVisitor     the filter by visitor context that provides methods to retrieve attribute schema, check entity type knowledge,
	 *                            and apply filters on index
	 * @return a Formula object representing the filtering formula based on the given constraints and visitor context
	 */
	@Nonnull
	protected Formula translateInternal(
		@Nonnull AbstractAttributeFilterStringSearchConstraintLeaf attributeConstraint,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final String attributeName = attributeConstraint.getAttributeName();
		final String textToSearch = attributeConstraint.getTextToSearch();
		final Optional<GlobalAttributeSchemaContract> optionalGlobalAttributeSchema = getOptionalGlobalAttributeSchema(
			filterByVisitor, attributeName, AttributeTrait.FILTERABLE
		);

		if (filterByVisitor.isEntityTypeKnown() || optionalGlobalAttributeSchema.isPresent()) {
			final AttributeSchemaContract attributeDefinition = optionalGlobalAttributeSchema
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeDefinition);
			assertStringType(attributeConstraint, attributeDefinition);

			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			final AttributeFormula filteringFormula = new AttributeFormula(
				attributeDefinition instanceof GlobalAttributeSchemaContract,
				attributeKey,
				filterByVisitor.applyOnFilterIndexes(
					processingScope.getReferenceSchema(),
					attributeDefinition,
					index -> this.filterIndexResolver.apply(index, textToSearch)
				)
			);

			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filteringFormula,
					createAlternativeBitmapFilter(
						filterByVisitor, attributeConstraint, attributeName,
						value -> this.stringPredicate.test(value, textToSearch)
					)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute " + this.description + " filter",
				createAlternativeBitmapFilter(
					filterByVisitor, attributeConstraint, attributeName,
					value -> this.stringPredicate.test(value, textToSearch)
				)
			);
		}
	}

}
