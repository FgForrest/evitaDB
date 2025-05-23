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

package io.evitadb.core.query.sort.attribute.translator;

import com.carrotsearch.hppc.IntHashSet;
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
import static io.evitadb.index.attribute.SortIndex.createNormalizerFor;
import static java.util.Optional.ofNullable;

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
	 * Optional reference to the next comparator in the chain, defining the order in case the current comparator
	 * cannot determine the comparison result.
	 */
	@Nullable private final ReferenceComparator nextComparator;
	/**
	 * Functional interface used to retrieve the specific attribute value from a given {@link ReferenceContract}.
	 */
	@Nonnull private final Function<ReferenceContract, Serializable> attributeValueFetcher;
	/**
	 * Comparator for directly comparing the fetched attribute values between two {@link ReferenceContract} instances.
	 */
	@Nonnull private final Comparator<Serializable> comparator;
	/**
	 * Set containing the primary keys of references that were not sorted due to missing or null attribute values.
	 */
	private IntHashSet nonSortedReferences;

	public ReferenceAttributeReferenceComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection
	) {
		this(attributeName, type, locale, orderDirection, null);
	}

	public ReferenceAttributeReferenceComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection,
		@Nullable ReferenceComparator nextComparator
	) {
		final ComparatorSource comparatorSource = new ComparatorSource(
			type, orderDirection, OrderBehaviour.NULLS_LAST
		);
		final Optional<UnaryOperator<Serializable>> normalizerFor = createNormalizerFor(comparatorSource);
		final UnaryOperator<Serializable> normalizer = normalizerFor.orElseGet(UnaryOperator::identity);
		//noinspection unchecked
		this.comparator = createComparatorFor(locale, comparatorSource);
		this.attributeValueFetcher = locale == null ?
			referenceContract -> normalizer.apply(referenceContract.getAttribute(attributeName)) :
			referenceContract -> normalizer.apply(referenceContract.getAttribute(attributeName, locale));
		this.nextComparator = nextComparator;
	}

	private ReferenceAttributeReferenceComparator(
		@Nonnull ReferenceComparator nextComparator,
		@Nonnull Function<ReferenceContract, Serializable> attributeValueFetcher,
		@Nonnull Comparator<Serializable> comparator
	) {
		this.nextComparator = nextComparator;
		this.attributeValueFetcher = attributeValueFetcher;
		this.comparator = comparator;
	}

	@Nonnull
	@Override
	public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
		return new ReferenceAttributeReferenceComparator(comparatorForUnknownRecords, this.attributeValueFetcher, this.comparator);
	}

	@Nullable
	@Override
	public ReferenceComparator getNextComparator() {
		return this.nextComparator;
	}

	@Override
	public int getNonSortedReferenceCount() {
		return ofNullable(this.nonSortedReferences)
			.map(IntHashSet::size)
			.orElse(0);
	}

	@Override
	public int compare(ReferenceContract o1, ReferenceContract o2) {
		final Serializable attribute1 = o1 == null ? null : this.attributeValueFetcher.apply(o1);
		final Serializable attribute2 = o2 == null ? null : this.attributeValueFetcher.apply(o2);
		if (attribute1 != null && attribute2 != null) {
			return this.comparator.compare(attribute1, attribute2);
		} else if (attribute1 == null && attribute2 != null) {
			this.nonSortedReferences = ofNullable(this.nonSortedReferences)
				.orElseGet(IntHashSet::new);
			if (o1 != null) {
				this.nonSortedReferences.add(o1.getReferencedPrimaryKey());
			}
			return 1;
		} else if (attribute1 != null) {
			this.nonSortedReferences = ofNullable(this.nonSortedReferences)
				.orElseGet(IntHashSet::new);
			if (o2 != null) {
				this.nonSortedReferences.add(o2.getReferencedPrimaryKey());
			}
			return -1;
		} else {
			this.nonSortedReferences = ofNullable(this.nonSortedReferences)
				.orElseGet(IntHashSet::new);
			if (o1 != null) {
				this.nonSortedReferences.add(o1.getReferencedPrimaryKey());
			}
			if (o2 != null) {
				this.nonSortedReferences.add(o2.getReferencedPrimaryKey());
			}
			return 0;
		}
	}
}
