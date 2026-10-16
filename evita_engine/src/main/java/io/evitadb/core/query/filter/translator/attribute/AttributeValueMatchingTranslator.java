/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeValueMatcher;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implemented by the {@link FilteringConstraintTranslator translators} of the attribute constraints that test a
 * single attribute value - the comparisons, the set, the interval, the range envelope and the string searches. The
 * translator owns the per-value test of its constraint: it creates the {@link AttributeValueMatcher} that answers the
 * constraint from a value, without the index of the attribute.
 *
 * The matcher is the only per-value implementation of the constraint. The reference row filter asks it for the value
 * of a reference row, and the main query asks it - through {@link #createAlternativeBitmapFilter} and
 * {@link #createEntityFilteringBitmapFilter} - for the values of the prefetched entities, wherever the index of the
 * attribute does not answer.
 *
 * A translator of an attribute constraint that is no test of a single value (`attributeIs` asks whether a value
 * exists at all) does not implement it - the exceptions are named by the invariant
 * {@link io.evitadb.core.query.filter.FilterByVisitor} checks when it is loaded.
 *
 * @param <T> the translated constraint
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface AttributeValueMatchingTranslator<T extends FilterConstraint & AttributeConstraint<FilterConstraint>> {

	/**
	 * Creates the matcher of the constraint over the attribute. The conversion and the normalization of the constraint
	 * value are paid here, once, so the matcher can be applied to any number of values.
	 *
	 * @param constraint        the constraint
	 * @param attributeSchema   the schema of the attribute the constraint is applied to
	 * @param attributeIndexKey key of the filter index the attribute is read from, its locale selects the comparator
	 * @param now               supplier of the moment of the query, consulted by a constraint that names no moment of
	 *                          its own
	 * @return the matcher
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the constraint cannot be applied to the attribute
	 */
	@Nonnull
	AttributeValueMatcher createMatcher(
		@Nonnull T constraint,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Supplier<OffsetDateTime> now
	);

	/**
	 * Tells whether the main query answers the constraint from the unique index of the attribute where it has one.
	 * That is the lookup policy of the operator - `attributeEquals` and `attributeInSet` resolve a value in the unique
	 * index, every other operator reads the filter index - not a fact of the schema. It decides which locales of a
	 * localized attribute the constraint reads.
	 *
	 * @return true when the unique index of the attribute answers the constraint
	 */
	default boolean isAnsweredByUniqueIndex() {
		return false;
	}

	/**
	 * Returns the values of the constraint the main query encodes into a key of the filter index of the attribute - the
	 * probe of an equality, of a comparison or the bounds of an interval over a scalar attribute. A `BigDecimal` among
	 * them must fit the key (see {@link #assertIndexable}). The values are returned as the constraint holds them,
	 * an array standing for its elements.
	 *
	 * @param constraint the constraint
	 * @return the values, none for a constraint that probes the index with no value of its own
	 */
	@Nonnull
	default Serializable[] getIndexProbeValues(@Nonnull T constraint) {
		return ArrayUtils.EMPTY_SERIALIZABLE_ARRAY;
	}

	/**
	 * Refuses a decimal value of the constraint that the filter index of the attribute cannot encode. Such a value is
	 * outside the domain of a `BigDecimal` attribute the index holds: its keys are `int` numbers of the indexed decimal
	 * places, so the index could never store the value, and the main query does not let it reach the index or the
	 * values of the prefetched entities. The rule follows the schema alone, not the plan, and applies when the
	 * attribute has a filter index in at least one of the requested scopes; an attribute no index holds is compared
	 * as it is, with no limit.
	 *
	 * @param constraint      the constraint
	 * @param attributeSchema the schema of the attribute the constraint is applied to
	 * @param requestedScopes the scopes of the query
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when a decimal value does not fit the index key
	 */
	default void assertIndexable(
		@Nonnull T constraint,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Scope> requestedScopes
	) {
		if (BigDecimal.class.equals(attributeSchema.getPlainType())) {
			boolean indexed = false;
			for (final Scope scope : requestedScopes) {
				if (attributeSchema.hasFilterIndexInScope(scope)) {
					indexed = true;
					break;
				}
			}
			if (indexed) {
				final int indexedDecimalPlaces = attributeSchema.getIndexedDecimalPlaces();
				for (final Serializable probeValue : getIndexProbeValues(constraint)) {
					if (probeValue instanceof Object[] elements) {
						for (final Object element : elements) {
							assertEncodable(element, indexedDecimalPlaces);
						}
					} else {
						assertEncodable(probeValue, indexedDecimalPlaces);
					}
				}
			}
		}
	}

	/**
	 * Creates the matcher of the constraint for the main query: refuses a decimal value the index cannot encode (see
	 * {@link #assertIndexable}) and creates the {@link #createMatcher matcher} over the attribute as the filter index
	 * keys it in the locale of the query.
	 *
	 * @param constraint      the constraint
	 * @param filterByVisitor the visitor translating the query
	 * @param attributeSchema the schema of the attribute the constraint is applied to
	 * @return the matcher
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the constraint cannot be applied to the attribute
	 */
	@Nonnull
	default AttributeValueMatcher createMainQueryMatcher(
		@Nonnull T constraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		assertIndexable(constraint, attributeSchema, processingScope.getScopes());
		return createMatcher(
			constraint,
			attributeSchema,
			AttributeIndex.createAttributeKey(
				processingScope.getReferenceSchema(), attributeSchema, filterByVisitor.getLocale()
			),
			filterByVisitor::getNow
		);
	}

	/**
	 * Creates the filter of the prefetched entities that tests the values of the attribute with an already created
	 * matcher. It is the one for a query where the schema of the attribute is known when the constraint is translated:
	 * the matcher, created by {@link #createMainQueryMatcher}, serves every entity.
	 *
	 * @param constraint      the constraint
	 * @param filterByVisitor the visitor translating the query
	 * @param matcher         the matcher of the constraint over the attribute
	 * @param traits          the traits the attribute schema must have
	 * @return the filter
	 */
	@Nonnull
	default AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull T constraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeValueMatcher matcher,
		@Nonnull AttributeTrait... traits
	) {
		return createBitmapFilter(constraint, filterByVisitor, attributeSchema -> matcher, traits);
	}

	/**
	 * Creates the filter of the entities found without the schema of the attribute being known, which happens when the
	 * query names no collection and the attribute is not a catalog one: the matcher is created by
	 * {@link #createMainQueryMatcher} for the schema each type of the examined entities has the attribute with.
	 *
	 * @param constraint      the constraint
	 * @param filterByVisitor the visitor translating the query
	 * @param traits          the traits the attribute schema must have
	 * @return the filter
	 */
	@Nonnull
	default AttributeBitmapFilter createEntityFilteringBitmapFilter(
		@Nonnull T constraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeTrait... traits
	) {
		return createBitmapFilter(
			constraint, filterByVisitor,
			attributeSchema -> createMainQueryMatcher(constraint, filterByVisitor, attributeSchema),
			traits
		);
	}

	/**
	 * Wires the filter over the processing scope of the visitor: the schema accessor of the scope, the attribute values
	 * the scope reads in the locale of the query, and the attribute as the content to prefetch.
	 *
	 * @param constraint      the constraint
	 * @param filterByVisitor the visitor translating the query
	 * @param matcherFactory  creates the matcher from the schema of the attribute
	 * @param traits          the traits the attribute schema must have
	 * @return the filter
	 */
	@Nonnull
	private AttributeBitmapFilter createBitmapFilter(
		@Nonnull T constraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Function<AttributeSchemaContract, AttributeValueMatcher> matcherFactory,
		@Nonnull AttributeTrait... traits
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return AttributeBitmapFilter.matching(
			constraint.getAttributeName(),
			processingScope::getAttributeSchema,
			(entity, attributeName) -> processingScope.getAttributeValueStream(
				entity, attributeName, filterByVisitor.getLocale()
			),
			matcherFactory,
			traits
		);
	}

	/**
	 * Refuses a single decimal value the filter index cannot encode with the very message of the encoding.
	 *
	 * @param value                the value of the constraint, NULL for an open bound
	 * @param indexedDecimalPlaces the decimal places the attribute is indexed with
	 */
	private static void assertEncodable(@Nullable Object value, int indexedDecimalPlaces) {
		if (value != null) {
			NumberUtils.convertExternalNumberToInt(
				EvitaDataTypes.toTargetType((Serializable) value, BigDecimal.class), indexedDecimalPlaces
			);
		}
	}

}
