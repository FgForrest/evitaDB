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

package io.evitadb.core.query.algebra.price.termination;

import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * DTO that is connected to {@link PriceTerminationFormula} allowing to optimize formula tree in the such way,
 * that terminating formula with same price evaluation context will be replaced by single instance - taking advantage
 * of result memoization.
 *
 * @param validIn            the moment prices must be valid at, reduced through
 *                           {@link DateTimeRange#toComparableLong(OffsetDateTime)} so it discriminates at exactly the
 *                           granularity the validity range index answers at — a coarser key would let two probes that
 *                           select different prices share one memoized result. {@code Long.MIN_VALUE} means "no
 *                           validity constraint", which no real moment can reach (a moment saturates one step short
 *                           of it).
 * @param targetPriceIndexes ordered, immutable list of the price indexes the formula targets, retrieved from the local
 *                           scope of the input query; the order is part of the context's identity.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record PriceEvaluationContext(
	long validIn,
	@Nonnull List<PriceIndexKey> targetPriceIndexes
) implements Serializable {
	@Serial private static final long serialVersionUID = -2132423408087460595L;

	/** Copies target indexes into an immutable ordered value. */
	public PriceEvaluationContext {
		targetPriceIndexes = List.copyOf(targetPriceIndexes);
	}

	/** Builds a context from defensively copied array input. */
	public PriceEvaluationContext(long validIn, @Nonnull PriceIndexKey... targetPriceIndexes) {
		this(validIn, List.of(targetPriceIndexes));
	}

	/** Converts an optional validity moment to the range index's precision. */
	public PriceEvaluationContext(
		@Nullable OffsetDateTime validIn,
		@Nonnull PriceIndexKey... targetPriceIndexes
	) {
		this(
			validIn == null ? Long.MIN_VALUE : DateTimeRange.toComparableLong(validIn),
			targetPriceIndexes
		);
		Assert.isPremiseValid(
			!ArrayUtils.isEmpty(targetPriceIndexes),
			"Expected at least one target price index identification!"
		);
	}

	@Nonnull
	@Override
	public String toString() {
		// `validIn` is an epoch MILLISECOND, so it decomposes into whole seconds plus a millisecond remainder;
		// `floorDiv`/`floorMod` rather than `/` and `%` because a pre-1970 moment is negative and the truncating
		// operators would place it one second late. The `Long.MIN_VALUE` "no validity constraint" sentinel is
		// filtered out first, so what reaches the decomposition is always a real moment.
		return this.targetPriceIndexes.toString() + (this.validIn == Long.MIN_VALUE ? "" : " validIn: " +
			LocalDateTime.ofEpochSecond(
				Math.floorDiv(this.validIn, 1000L),
				(int) Math.floorMod(this.validIn, 1000L) * 1_000_000,
				ZoneOffset.UTC
			));
	}

}
