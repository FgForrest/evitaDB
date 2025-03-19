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

import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraverseByEntityProperty;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter.MergeMode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Attribute comparator sorts entities according to a specified attribute value. It needs to provide a function for
 * accessing the entity attribute value and the simple {@link Comparable} comparator implementation. This implementation
 * adheres to {@link MergeMode#APPEND_ALL} which relates to {@link TraverseByEntityProperty} ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class TraverseReferenceAttributeComparator extends AbstractReferenceAttributeComparator {
	@Serial private static final long serialVersionUID = 2969632214608241409L;

	public TraverseReferenceAttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection,
		@Nonnull Supplier<IntIntMap> referencePositionMapSupplier
	) {
		super(
			attributeName,
			type,
			referenceSchema,
			locale,
			orderDirection,
			referencePositionMapSupplier
		);
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue attribute1 = this.attributeValueFetcher.apply(o1);
		final ReferenceAttributeValue attribute2 = this.attributeValueFetcher.apply(o2);
		if (attribute1 != null && attribute2 != null) {
			final int pos1 = this.referencePositionMap.get(attribute1.referencedEntityPrimaryKey());
			final int pos2 = this.referencePositionMap.get(attribute2.referencedEntityPrimaryKey());
			final int firstComparison = Integer.compare(pos1, pos2);
			if (firstComparison == 0) {
				final int result = attribute1.compareTo(attribute2);
				if (result == 0) {
					return this.pkComparator.compare(o1, o2);
				} else {
					return result;
				}
			} else {
				return firstComparison;
			}
		} else if (attribute1 == null && attribute2 != null) {
			return 1;
		} else if (attribute1 != null) {
			return -1;
		} else {
			return 0;
		}
	}

}
