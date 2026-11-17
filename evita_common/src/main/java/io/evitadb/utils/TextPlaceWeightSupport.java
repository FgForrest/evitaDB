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

package io.evitadb.utils;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Bounds decimal representations before canonicalizing fulltext place weights. */
public final class TextPlaceWeightSupport {
	/** Maximum significant precision and absolute stripped scale accepted by a place weight. */
	private static final int REPRESENTATION_LIMIT = 100;
	/** Bounded diagnostic: rejected values must never be expanded just to report a refusal. */
	private static final String REPRESENTATION_ERROR =
		"Place weights require at most 100 significant digits and a stripped scale between -100 and 100!";

	/** Prevents construction of this normalization helper. */
	private TextPlaceWeightSupport() { }

	/**
	 * Canonicalizes zero and insignificant scale after checking representation bounds. A nonzero input whose raw
	 * scale is already too negative cannot enter the supported interval by stripping zeros; refusing it first also
	 * prevents `stripTrailingZeros` from underflowing `Integer.MIN_VALUE`. Stripping removes one zero per division,
	 * so the input is cut to at most 300 digits first: no accepted value has more than 200 integer digits, and a
	 * raw scale above 100 is reduced by a single exact division. Rescaling allocates at most 100 padding digits.
	 *
	 * @param weight the place weight, in any scale
	 * @return `BigDecimal.ZERO` for zero, otherwise the value without trailing fractional zeros and with a scale of
	 *         at least 0, so that equal numbers yield equal constraints and print the same plain text
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when negative or outside the precision or scale bounds
	 */
	@Nonnull
	public static BigDecimal normalize(@Nonnull BigDecimal weight) {
		Assert.isTrue(weight.signum() >= 0, "Place weights must be non-negative!");
		if (weight.signum() == 0) {
			return BigDecimal.ZERO;
		}
		Assert.isTrue(weight.scale() >= -REPRESENTATION_LIMIT, REPRESENTATION_ERROR);
		// stripping keeps the integer digits (precision - scale), and an accepted value has at most 100 - (-100)
		Assert.isTrue(weight.precision() - (long) weight.scale() <= 2L * REPRESENTATION_LIMIT, REPRESENTATION_ERROR);
		final BigDecimal bounded;
		if (weight.scale() > REPRESENTATION_LIMIT) {
			// a nonzero value below 10^-100 can never strip to an accepted scale
			Assert.isTrue(
				(long) weight.scale() - REPRESENTATION_LIMIT < weight.precision(), REPRESENTATION_ERROR
			);
			bounded = weight.setScale(REPRESENTATION_LIMIT, RoundingMode.DOWN);
			// a truncation that drops a nonzero digit means the stripped scale would exceed the limit
			Assert.isTrue(bounded.compareTo(weight) == 0, REPRESENTATION_ERROR);
		} else {
			bounded = weight;
		}
		final BigDecimal stripped = bounded.stripTrailingZeros();
		Assert.isTrue(
			stripped.precision() <= REPRESENTATION_LIMIT &&
				stripped.scale() >= -REPRESENTATION_LIMIT && stripped.scale() <= REPRESENTATION_LIMIT,
			REPRESENTATION_ERROR
		);
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}
}
