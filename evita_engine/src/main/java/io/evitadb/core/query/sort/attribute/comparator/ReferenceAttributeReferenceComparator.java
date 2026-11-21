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

package io.evitadb.core.query.sort.attribute.comparator;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.index.attribute.SortIndex.createComparatorFor;
import static io.evitadb.index.attribute.SortIndex.createComparatorNormalizerFor;

/**
 * Reference attribute comparator sorts {@link ReferenceDecorator} according to a specified reference attribute value.
 * It needs to provide a function for accessing the entity attribute value and the simple {@link Comparable} comparator
 * implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("ComparatorNotSerializable")
public class ReferenceAttributeReferenceComparator implements ReferenceComparator {
	/**
	 * Retrieves the raw attribute value from a reference.
	 */
	@Nonnull private final Function<ReferenceContract, Serializable> attributeValueFetcher;
	/**
	 * Normalizes values for comparison.
	 */
	@Nonnull private final UnaryOperator<Serializable> normalizer;
	/**
	 * Comparator for directly comparing the fetched attribute values between two {@link ReferenceContract} instances.
	 */
	@Nonnull private final Comparator<Serializable> comparator;

	/**
	 * Creates the comparator of the references by the given reference attribute.
	 *
	 * @param attributeName        the name of the reference attribute
	 * @param type                 the type of the attribute value
	 * @param indexedDecimalPlaces the `indexedDecimalPlaces` of the attribute schema - the scale a range value is
	 *                             compared at
	 * @param locale               the locale to read a localized attribute in, or `null`
	 * @param orderDirection       the direction to sort in
	 */
	public ReferenceAttributeReferenceComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		int indexedDecimalPlaces,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection
	) {
		final ComparatorSource comparatorSource = new ComparatorSource(
			type, orderDirection, OrderBehaviour.NULLS_LAST
		);
		final Optional<UnaryOperator<Serializable>> normalizerFor =
			createComparatorNormalizerFor(comparatorSource, indexedDecimalPlaces);
		this.normalizer = normalizerFor.orElseGet(UnaryOperator::identity);
		//noinspection unchecked
		this.comparator = createComparatorFor(locale, comparatorSource);
		this.attributeValueFetcher = locale == null ?
			referenceContract -> referenceContract.getAttribute(attributeName) :
			referenceContract -> referenceContract.getAttribute(attributeName, locale);
	}

	/**
	 * Checks whether the reference carries a value for the ordered attribute.
	 *
	 * @param reference row to classify
	 * @return whether the value is present
	 */
	@Override
	public boolean canRank(@Nonnull ReferenceContract reference) {
		return this.attributeValueFetcher.apply(reference) != null;
	}

	/**
	 * Compares references by their normalized attribute values, with missing values at the end.
	 *
	 * @param o1 first row
	 * @param o2 second row
	 * @return the order of the rows
	 */
	@Override
	public int compare(@Nullable ReferenceContract o1, @Nullable ReferenceContract o2) {
		final Serializable attribute1 = o1 == null ? null : this.attributeValueFetcher.apply(o1);
		final Serializable attribute2 = o2 == null ? null : this.attributeValueFetcher.apply(o2);
		if (attribute1 == null) {
			return attribute2 == null ? 0 : 1;
		}
		return attribute2 == null
			? -1
			: this.comparator.compare(this.normalizer.apply(attribute1), this.normalizer.apply(attribute2));
	}
}
