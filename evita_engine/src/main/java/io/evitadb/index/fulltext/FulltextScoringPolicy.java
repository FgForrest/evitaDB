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
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;

/**
 * Rank-time schema defaults shared by the places and sources of a query. Query overrides arrive separately with
 * each scoring request; this policy supplies schema weights, impact tables and the engine tie breaker. Weights and
 * coefficient are fixed at construction; neither belongs to an index definition. Its cached impact tables and
 * structural hashes belong to the query that resolved it.
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
	/** Resolved composition weights, converted when the query policy is built. */
	@Nonnull private final Map<String, Float> referenceWeights;
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
	 * Identifies an own place or a target place composed through one particular owner reference.
	 *
	 * @param composingReference NULL for the owner's own places, its reference attributes included; the name of the
	 *                           owner's reference for a target place searched through composition
	 * @param key the place within the collection that holds it
	 */
	public record Place(@Nullable String composingReference, @Nonnull FulltextFieldKey key) { }

	/**
	 * Creates a query policy with the owner's resolved reference composition weights.
	 * @param weights effective weights of the owner's searchable places
	 * @param referenceWeights effective weights of composition references
	 */
	public FulltextScoringPolicy(
		@Nonnull Map<FulltextFieldKey, Float> weights, @Nonnull Map<String, Float> referenceWeights
	) {
		this(weights, referenceWeights, DEFAULT_TIE_BREAKER);
	}

	/**
	 * Creates a policy with an explicit coefficient and no reference weights. The public constructor uses the engine
	 * tie breaker; the package-private visibility lets the decision-table tests vary it.
	 * @param weights effective weights by full place identity
	 * @param tieBreaker fraction of the other places to add, finite and within 0–1
	 */
	FulltextScoringPolicy(@Nonnull Map<FulltextFieldKey, Float> weights, float tieBreaker) {
		this(weights, Map.of(), tieBreaker);
	}

	/**
	 * Stores the resolved engine weights and the coefficient used by impact tables.
	 * @param weights place weights
	 * @param referenceWeights reference composition weights
	 * @param tieBreaker fraction of the other places to add
	 */
	private FulltextScoringPolicy(
		@Nonnull Map<FulltextFieldKey, Float> weights, @Nonnull Map<String, Float> referenceWeights, float tieBreaker
	) {
		Assert.isPremiseValid(Float.isFinite(tieBreaker) && tieBreaker >= 0 && tieBreaker <= 1,
			"The fulltext tie breaker must be finite and within 0–1!");
		for (final float weight : weights.values()) {
			validateWeight(weight);
		}
		this.weights = Map.copyOf(weights);
		for (final float weight : referenceWeights.values()) {
			validateWeight(weight);
		}
		this.referenceWeights = Map.copyOf(referenceWeights);
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
	 * Reads a reference weight already converted while resolving the owner policy. Missing entries are programming
	 * errors, not engine defaults.
	 * @param referenceName composing reference of the owner
	 * @return the reference's engine weight
	 */
	public float referenceWeight(@Nonnull String referenceName) {
		final Float weight = this.referenceWeights.get(referenceName);
		Assert.isPremiseValid(weight != null, () -> "No fulltext reference weight for " + referenceName + "!");
		return weight;
	}

	/**
	 * Hashes schema weights, place identities and the coefficient independently of map iteration order. Reference
	 * weights stay out of this hash: a source that composes through a reference adds that reference's weight bits
	 * to its own source identity in `TextMatchesTranslator`.
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
