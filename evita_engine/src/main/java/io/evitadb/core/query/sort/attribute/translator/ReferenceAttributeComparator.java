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

package io.evitadb.core.query.sort.attribute.translator;

import com.carrotsearch.hppc.IntHashSet;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * Reference attribute comparator sorts {@link ReferenceDecorator} according to a specified reference attribute value.
 * It needs to provide a function for accessing the entity attribute value and the simple {@link Comparable} comparator
 * implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("ComparatorNotSerializable")
public class ReferenceAttributeComparator implements ReferenceComparator {
	@Nullable private final ReferenceComparator nextComparator;
	@Nonnull private final Function<ReferenceContract, Comparable<?>> attributeValueFetcher;
	@Nonnull private final Comparator<Comparable<?>> comparator;
	private IntHashSet nonSortedReferences;

	public ReferenceAttributeComparator(
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull Comparator<Comparable<?>> comparator
	) {
		this(attributeName, locale, comparator, null);
	}

	public ReferenceAttributeComparator(
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull Comparator<Comparable<?>> comparator,
		@Nullable ReferenceComparator nextComparator
	) {
		this.comparator = comparator;
		this.attributeValueFetcher = locale == null ?
			referenceContract -> referenceContract.getAttribute(attributeName) :
			referenceContract -> referenceContract.getAttribute(attributeName, locale);
		this.nextComparator = nextComparator;
	}

	private ReferenceAttributeComparator(
		@Nonnull ReferenceComparator nextComparator,
		@Nonnull Function<ReferenceContract, Comparable<?>> attributeValueFetcher,
		@Nonnull Comparator<Comparable<?>> comparator
	) {
		this.nextComparator = nextComparator;
		this.attributeValueFetcher = attributeValueFetcher;
		this.comparator = comparator;
	}

	@Nonnull
	@Override
	public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
		return new ReferenceAttributeComparator(comparatorForUnknownRecords, attributeValueFetcher, comparator);
	}

	@Nullable
	@Override
	public ReferenceComparator getNextComparator() {
		return nextComparator;
	}

	@Override
	public int getNonSortedReferenceCount() {
		return ofNullable(nonSortedReferences)
			.map(IntHashSet::size)
			.orElse(0);
	}

	@Override
	public int compare(ReferenceContract o1, ReferenceContract o2) {
		final Comparable<?> attribute1 = o1 == null ? null : attributeValueFetcher.apply(o1);
		final Comparable<?> attribute2 = o2 == null ? null : attributeValueFetcher.apply(o2);
		if (attribute1 != null && attribute2 != null) {
			return comparator.compare(attribute1, attribute2);
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
