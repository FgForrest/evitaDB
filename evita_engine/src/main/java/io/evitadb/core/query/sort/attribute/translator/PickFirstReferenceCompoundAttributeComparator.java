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

package io.evitadb.core.query.sort.attribute.translator;


import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter.MergeMode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Comparator for sorting entities according to a sortable compound attribute value. It combines multiple attribute
 * comparators into one. This implementation adheres to {@link MergeMode#APPEND_FIRST} which relates
 * to {@link PickFirstByEntityProperty} ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class PickFirstReferenceCompoundAttributeComparator extends AbstractReferenceCompoundAttributeComparator {
	@Serial private static final long serialVersionUID = 2199278500724685085L;

	/**
	 * Supplier of the index of the referenced id positions in the main ordering.
	 */
	@Nonnull private final Supplier<IntIntMap> referencePositionMapSupplier;
	/**
	 * Memoized result from {@link #referencePositionMapSupplier} supplier.
	 */
	private IntIntMap referencePositionMap;

	public PickFirstReferenceCompoundAttributeComparator(
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull ReferenceSchema referenceSchema,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaExtractor,
		@Nonnull OrderDirection orderDirection,
		@Nonnull Supplier<IntIntMap> referencePositionMapSupplier
	) {
		super(
			compoundSchemaContract,
			referenceSchema,
			locale,
			attributeSchemaExtractor,
			orderDirection
		);
		this.referencePositionMapSupplier = referencePositionMapSupplier;
	}

	@Override
	@Nonnull
	protected Optional<ReferenceContract> pickReference(@Nonnull EntityContract entity) {
		// initialize the reference position map if it hasn't been initialized yet
		if (this.referencePositionMap == null) {
			this.referencePositionMap = this.referencePositionMapSupplier.get();
		}
		// find the reference contract that has the attribute we are looking for
		return entity.getReferences(this.referenceSchema.getName())
			.stream()
			.filter(it -> Arrays.stream(this.attributeElements).anyMatch(ae -> it.getAttribute(ae.attributeName()) != null))
			.min(Comparator.comparingInt(it -> this.referencePositionMap.get(it.getReferencedPrimaryKey())));
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue valueToCompare1 = getAndMemoizeValue(o1);
		final ReferenceAttributeValue valueToCompare2 = getAndMemoizeValue(o2);
		if (valueToCompare1 != ReferenceAttributeValue.MISSING && valueToCompare2 != ReferenceAttributeValue.MISSING) {
			return valueToCompare1.compareTo(valueToCompare2);
		} else {
			if (valueToCompare1 != ReferenceAttributeValue.MISSING) {
				return -1;
			} else if (valueToCompare2 != ReferenceAttributeValue.MISSING) {
				return 1;
			} else {
				return 0;
			}
		}
	}

}
