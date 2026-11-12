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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.identity.ConstructionMode;
import io.evitadb.test.identity.IdentityFixture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real construction inputs for {@link AttributeHistogramComputer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class AttributeHistogramComputerIdentityFixture implements IdentityFixture<AttributeHistogramComputer> {
	/** Covers optional filtering, schema precision, index versions and comparator opacity. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<AttributeHistogramComputer>> modes() {
		final Formula first = leaf(1);
		final Formula second = leaf(2);
		final FilterIndex index = new OwnerFilterIndex(
			new AttributeIndexKey(null, "amount", null), BigDecimal.class
		);
		final FilterIndex otherIndex = new OwnerFilterIndex(
			new AttributeIndexKey(null, "amount", null), BigDecimal.class
		);
		final List<FilterIndex> indexes = List.of(index);
		final Formula opaqueFilter = opaqueLeaf();
		return Map.of("request", new ConstructionMode<>(
			() -> create("amount", first, 10, HistogramBehavior.STANDARD,
				BigDecimal.class, 2, indexes, Comparator.naturalOrder()),
			Map.of(
				"attributeName", () -> create("other", first, 10, HistogramBehavior.STANDARD,
					BigDecimal.class, 2, indexes, Comparator.naturalOrder()),
				"filterFormula", () -> create("amount", second, 10, HistogramBehavior.STANDARD,
					BigDecimal.class, 2, indexes, Comparator.naturalOrder()),
				"absent filter", () -> create("amount", null, 10, HistogramBehavior.STANDARD,
					BigDecimal.class, 2, indexes, Comparator.naturalOrder()),
				"bucketCount", () -> create("amount", first, 11, HistogramBehavior.STANDARD,
					BigDecimal.class, 2, indexes, Comparator.naturalOrder()),
				"behavior", () -> create("amount", first, 10, HistogramBehavior.OPTIMIZED,
					BigDecimal.class, 2, indexes, Comparator.naturalOrder()),
				"schemaType", () -> create("amount", first, 10, HistogramBehavior.STANDARD,
					Long.class, 2, indexes, Comparator.naturalOrder()),
				"indexedDecimalPlaces", () -> create("amount", first, 10, HistogramBehavior.STANDARD,
					BigDecimal.class, 3, indexes, Comparator.naturalOrder()),
				"attributeIndexes", () -> create("amount", first, 10, HistogramBehavior.STANDARD,
					BigDecimal.class, 2, List.of(otherIndex), Comparator.naturalOrder())
			),
			Map.of(
				"comparator", () -> create("amount", first, 10, HistogramBehavior.STANDARD,
					BigDecimal.class, 2, indexes, Comparator.reverseOrder()),
				"filterFormula", () -> create("amount", opaqueFilter, 10, HistogramBehavior.STANDARD,
					BigDecimal.class, 2, indexes, Comparator.naturalOrder())
			),
			Map.of("attributeIndexes", () -> create("amount", first, 10, HistogramBehavior.STANDARD,
				BigDecimal.class, 2, Collections.nCopies(TokenList.CAP + 1, index), Comparator.naturalOrder())),
			Map.of(), Set.of(), Set.of(), Set.of()
		));
	}

	/** Builds a request over the supplied schema and shared real indexes. */
	@Nonnull
	static AttributeHistogramComputer create(
		@Nonnull String name, @Nullable Formula filter, int buckets, @Nonnull HistogramBehavior behavior,
		@Nonnull Class<? extends Serializable> type, int places, @Nonnull List<FilterIndex> indexes,
		@Nonnull Comparator<?> comparator
	) {
		final AttributeSchema schema = AttributeSchema._internalBuild(
			"amount", null, null, Map.of(), Set.of(), Map.of(), Set.of(), false, false, false,
			type, null, places, ConflictResolutionOverride.INHERITED
		);
		return new AttributeHistogramComputer(name, filter, buckets, behavior,
			new AttributeHistogramProducer.AttributeHistogramRequest(
				schema, 10, HistogramBehavior.STANDARD, comparator, indexes
			));
	}

	/** Builds a fresh bounded content formula. */
	@Nonnull
	static Formula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}

	/** Builds an unowned input whose content exceeds the exact-content bound. */
	@Nonnull
	static Formula opaqueLeaf() {
		final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		return new ConstantFormula(new BaseBitmap(values));
	}
}
