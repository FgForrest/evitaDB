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

package io.evitadb.index.fulltext;

import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;

/**
 * Rank-time policy shared by the places and sources of a query. The planner resolves the weights from the processing
 * schema's search weights and uses the engine tie breaker; both are fixed at construction, and neither belongs to an
 * index definition. The weight tables and hashes it caches are confined to the query that resolved it.
 *
 * Within each place, each query word contributes its best weighted impact once, including split words. These
 * contributions form a saturating unsigned 16-bit sum; places and sources combine by best + t × others.
 *
 * Strength occupies bits 39–24, immediately below exactness. Its unsigned fixed point has scale 8, resolution
 * 0.125 and usable range 0–8191.875. Larger values saturate. Weight multiplication is tabulated using exact integer
 * arithmetic over the binary float mantissa and exponent, rounded half up. Final composition uses integer
 * arithmetic over the coefficient's exact binary rational, so identical inputs produce identical bits on every replica.
 */
@NotThreadSafe
public final class FulltextScoringPolicy {
	/** Tie breaker of every policy the planner resolves. */
	private static final float DEFAULT_TIE_BREAKER = 0.3f;
	/** Fixed-point units per raw impact unit. */
	public static final int STRENGTH_SCALE = 8;
	/** Maximum unsigned strength. */
	public static final int MAX_STRENGTH = 0xFFFF;
	/** Offset of the strength lane. */
	public static final int STRENGTH_SHIFT = 24;
	/** Mask selecting only the quality lanes. */
	public static final long QUALITY_MASK = 0xFFFFFF0000000000L;
	/** Resolved place weights keyed by full place identity, including kind and reference context. */
	@Nonnull private final Map<FulltextFieldKey, Float> weights;
	/** Effective coefficient, retained for structural hashing. */
	private final float tieBreaker;
	/** Numerator of the coefficient's exact binary rational. */
	private final int numerator;
	/** Power-of-two denominator of the coefficient's exact binary rational. */
	private final int denominatorShift;
	/** Query-local tables shared by all formulas using the same effective weight bits. */
	@Nonnull private final IntObjectHashMap<char[]> impactTables = new IntObjectHashMap<>(4);
	/** Structural hashes memoized by hash implementation; policies belong to one query thread. */
	@Nonnull private final Map<LongHashFunction, Long> hashes = CollectionUtils.createHashMap(2);

	/**
	 * Creates a policy from effective place weights with the engine tie breaker.
	 * @param weights effective weights, resolved before looking up index data
	 */
	public FulltextScoringPolicy(@Nonnull Map<FulltextFieldKey, Float> weights) {
		this(weights, DEFAULT_TIE_BREAKER);
	}

	/**
	 * Creates a policy with an explicit coefficient. The public constructor delegates here with the engine tie
	 * breaker; the package-private visibility lets the decision-table tests vary it.
	 * @param weights effective weights by full place identity
	 * @param tieBreaker fraction of the other places to add, finite and within 0–1
	 */
	FulltextScoringPolicy(@Nonnull Map<FulltextFieldKey, Float> weights, float tieBreaker) {
		Assert.isPremiseValid(Float.isFinite(tieBreaker) && tieBreaker >= 0 && tieBreaker <= 1,
			"The fulltext tie breaker must be finite and within 0–1!");
		for (final float weight : weights.values()) {
			validateWeight(weight);
		}
		this.weights = Map.copyOf(weights);
		this.tieBreaker = tieBreaker == 0 ? 0 : tieBreaker;
		final int bits = Float.floatToIntBits(this.tieBreaker);
		this.numerator = mantissaOf(bits);
		this.denominatorShift = denominatorShiftOf(bits);
	}

	/**
	 * Reads a resolved effective weight. Missing entries are programming errors, not engine defaults.
	 * @param key full place identity
	 * @return effective weight
	 */
	public float weight(@Nonnull FulltextFieldKey key) {
		final Float weight = this.weights.get(key);
		Assert.isPremiseValid(weight != null, () -> "No effective fulltext weight for " + key + "!");
		return weight;
	}

	/**
	 * Hashes effective weights, their grouping identities and the coefficient, independently of map iteration order.
	 * @param hashFunction structural hash implementation
	 * @return policy identity for formula hashing and query-local memoization
	 */
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		final Long memoized = this.hashes.get(hashFunction);
		if (memoized != null) {
			return memoized;
		}
		final long[] placeHashes = new long[this.weights.size() + 1];
		int index = 1;
		for (final Map.Entry<FulltextFieldKey, Float> entry : this.weights.entrySet()) {
			final FulltextFieldKey key = entry.getKey();
			placeHashes[index++] = hashFunction.hashLongs(new long[]{key.kind().ordinal(),
				key.referenceName() == null ? 0 : hashFunction.hashChars(key.referenceName()),
				hashFunction.hashChars(key.name()), Float.floatToIntBits(entry.getValue())});
		}
		Arrays.sort(placeHashes, 1, placeHashes.length);
		placeHashes[0] = Float.floatToIntBits(this.tieBreaker);
		final long hash = hashFunction.hashLongs(placeHashes);
		this.hashes.put(hashFunction, hash);
		return hash;
	}

	/**
	 * Tabulates one place's weighted raw impacts, without floating point work in a hit or final quantization.
	 * @param weight finite nonnegative effective weight
	 * @return 256 unsigned fixed-point values, indexed by raw impact
	 */
	@Nonnull
	public static char[] weightedImpacts(float weight) {
		validateWeight(weight);
		final int bits = Float.floatToIntBits(weight);
		final int mantissa = mantissaOf(bits);
		// weight = mantissa * 2^-denominatorShift exactly, subnormals included.
		// Scale 8 absorbs three denominator bits. The largest product needs only 32 bits.
		final int scaleShift = Integer.numberOfTrailingZeros(STRENGTH_SCALE);
		final int shift = denominatorShiftOf(bits) - scaleShift;
		final char[] result = new char[256];
		for (int impact = 1; impact < result.length; impact++) {
			final long product = (long) impact * mantissa;
			final long rounded;
			if (product == 0 || shift > 33) {
				rounded = 0;
			} else if (shift <= 0) {
				// Every nonzero product here is already above the unsigned 16-bit ceiling.
				rounded = MAX_STRENGTH;
			} else {
				rounded = (product + (1L << (shift - 1))) >>> shift;
			}
			result[impact] = (char) Math.min(MAX_STRENGTH, rounded);
		}
		return result;
	}

	/**
	 * Reuses a quantization table across every scoring call and formula of this query policy.
	 * @param weight effective place weight
	 * @return borrowed table indexed by raw impact; callers must never modify it
	 */
	@Nonnull
	public char[] getWeightedImpacts(float weight) {
		final int bits = Float.floatToIntBits(weight);
		char[] table = this.impactTables.get(bits);
		if (table == null) {
			table = weightedImpacts(weight);
			this.impactTables.put(bits, table);
		}
		return table;
	}

	/**
	 * Combines collapsed place strengths by best + t × others, then saturates once.
	 * The sum remains wide: saturating it before applying t would lose bonuses even below the output ceiling.
	 * @param best largest weighted place in fixed-point units
	 * @param sum sum of weighted places in fixed-point units, at least best
	 * @return saturated unsigned 16-bit strength
	 */
	public int combine(int best, long sum) {
		if (best == MAX_STRENGTH || this.numerator == 0 || sum == best) {
			return best;
		}
		final long others = sum - best;
		final long bonus;
		if (this.denominatorShift < 63 && others <= (Long.MAX_VALUE >>> 1) / this.numerator) {
			final long product = others * this.numerator;
			bonus = (product + (1L << (this.denominatorShift - 1))) >>> this.denominatorShift;
		} else {
			// Tiny coefficients or enormous place counts are valid, but never require lossy floating point fallback.
			bonus = BigInteger.valueOf(others).multiply(BigInteger.valueOf(this.numerator))
				.add(BigInteger.ONE.shiftLeft(this.denominatorShift - 1))
				.shiftRight(this.denominatorShift).longValueExact();
		}
		return (int) Math.min(MAX_STRENGTH, best + bonus);
	}

	/**
	 * Applies the engine reference-type weight after collapsing target strengths.
	 * @param strength best target strength in fixed-point units
	 * @return strength multiplied by 0.5, rounded half up
	 */
	public static int weightReferenceStrength(int strength) {
		return (strength + 1) >>> 1;
	}

	/**
	 * Extracts the unsigned fixed-point strength from a composite.
	 * @param composite quality and strength composite
	 * @return unsigned strength units
	 */
	public static int strength(long composite) {
		return (int) ((composite >>> STRENGTH_SHIFT) & MAX_STRENGTH);
	}

	/**
	 * Reads the integer significand of a finite nonnegative float, including the implicit leading bit of a normal
	 * value, so that the value equals it divided by 2 raised to {@link #denominatorShiftOf(int)}.
	 * @param bits raw IEEE 754 bits of the value
	 * @return the 24-bit significand of a normal value, the bare 23-bit fraction of a subnormal one or zero
	 */
	private static int mantissaOf(int bits) {
		return (bits & 0x7FFFFF) | (((bits >>> 23) & 255) == 0 ? 0 : 0x800000);
	}

	/**
	 * Reads the power of two that divides {@link #mantissaOf(int)} into the exact value of a finite nonnegative float.
	 * @param bits raw IEEE 754 bits of the value
	 * @return `150 - exponent` for a normal value and `149` for a subnormal one or zero; zero or negative from 2^23 up
	 */
	private static int denominatorShiftOf(int bits) {
		final int exponent = (bits >>> 23) & 255;
		return exponent == 0 ? 149 : 150 - exponent;
	}

	/**
	 * Validates a resolved place weight.
	 * @param weight finite nonnegative weight
	 */
	private static void validateWeight(float weight) {
		Assert.isPremiseValid(Float.isFinite(weight) && weight >= 0,
			"A fulltext place weight must be finite and nonnegative!");
	}
}
