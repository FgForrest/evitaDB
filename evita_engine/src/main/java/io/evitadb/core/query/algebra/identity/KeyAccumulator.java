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

package io.evitadb.core.query.algebra.identity;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.query.algebra.attribute.RequestedThresholdPredicate;
import io.evitadb.core.query.algebra.attribute.ThresholdComparison;
import io.evitadb.core.query.algebra.attribute.ThresholdRange;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.price.model.PriceIndexKey;
import net.openhft.hashing.Access;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Single-pass structural hash, retained weight, opacity and locality description.
 * Primitive overloads avoid boxing and each nested value feeds this same accumulator.
 * Every nullable overload mixes a presence marker first, so a null never hashes like an empty value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public final class KeyAccumulator {
	/** Hashes the content of text and decimal key components. */
	private static final LongHashFunction CONTENT_HASH = LongHashFunction.xx3();
	/** Shared character view for allocation-free hashing of any character sequence. */
	private static final Access<CharSequence> CHAR_ACCESS = Access.toCharSequence(ByteOrder.LITTLE_ENDIAN);
	/** Current structural hash. */
	private long hash;
	/** Saturating weight, including the owning key. */
	private int weight = 1;
	/** Own record has an opaque input. */
	private boolean opaque;
	/** Some component refuses cross-query use. */
	private boolean queryLocal;
	/** Some component transitively contains a side key. */
	private boolean sideKeys;

	/**
	 * Seeds the description with the declaring class identifier.
	 *
	 * @param classId the declaring holder discriminator mixed into the initial structural hash
	 */
	public KeyAccumulator(long classId) {
		this.hash = mix(0, classId);
	}

	/**
	 * Combines one primitive word with an avalanche mixer.
	 *
	 * @param seed the accumulated hash to combine with another primitive word
	 * @param value the next primitive word contributing to the structural hash
	 * @return the combined 64-bit hash; equality still requires comparing the underlying values
	 */
	public static long mix(long seed, long value) {
		long mixed = value + 0x9e3779b97f4a7c15L;
		mixed = (mixed ^ (mixed >>> 33)) * 0xff51afd7ed558ccdL;
		mixed = (mixed ^ (mixed >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return Long.rotateLeft(seed, 27) * 0x9e3779b97f4a7c15L ^ mixed ^ (mixed >>> 33);
	}

	/**
	 * Hashes one producer identifier qualified by its role, shared by producer tokens and compact token lists.
	 *
	 * @param id the transactional producer version identifying the operand
	 * @param role the operand interpretation included alongside its producer version
	 * @return the hash of the producer version and role, using the non-null token presence seed
	 */
	static long producerHash(long id, @Nonnull TokenRole role) {
		return mix(mix(1, id), role.ordinal());
	}

	/**
	 * Hashes every character without allocating a byte array or using String.hashCode.
	 *
	 * @param text the character content to read without copying or retaining the sequence
	 * @return the 64-bit content hash computed through the shared little-endian character view
	 */
	public static long contentHash(@Nonnull CharSequence text) {
		return CONTENT_HASH.hash(text, CHAR_ACCESS, 0, (long) text.length() * Character.BYTES);
	}

	/**
	 * Adds weights with saturation rather than overflow.
	 *
	 * @param left the nonnegative accumulated retained weight in cache weight units
	 * @param right the nonnegative retained weight to add in cache weight units
	 * @return the sum, capped at {@link Integer#MAX_VALUE} rather than overflowing
	 */
	static int sumWeight(int left, int right) {
		return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
	}

	/**
	 * Charges retained text by its length, one base unit plus one unit per complete block of 16 characters.
	 *
	 * @param length the nonnegative number of retained characters to charge
	 * @return one base weight unit plus one unit for each complete group of 16 characters
	 */
	static int textWeight(int length) {
		return 1 + length / 16;
	}

	/**
	 * Charges a retained decimal one base unit plus one unit per complete 64 bits of its unscaled bit length.
	 *
	 * @param unscaled the retained decimal's signed unscaled value whose bit length determines the charge
	 * @return one base weight unit plus the unscaled value's bit length divided by 64, rounded down
	 */
	static int decimalWeight(@Nonnull BigInteger unscaled) {
		return 1 + unscaled.bitLength() / 64;
	}

	/**
	 * Mixes a word without charging it as a separately retained component.
	 *
	 * @param value the primitive word mixed into this accumulator without increasing retained weight
	 */
	void mixValue(long value) {
		this.hash = mix(this.hash, value);
	}

	/**
	 * Adds retained weight.
	 *
	 * @param value the nonnegative number of retained weight units added with saturation
	 */
	private void charge(int value) {
		this.weight = sumWeight(this.weight, value);
	}

	/**
	 * Adds a boolean without boxing.
	 *
	 * @param value the boolean identity component encoded as zero or one and charged one weight unit
	 */
	public void add(boolean value) {
		mixValue(value ? 1 : 0);
		charge(1);
	}

	/**
	 * Adds an integer without boxing.
	 *
	 * @param value the integer identity component mixed as one word and charged one weight unit
	 */
	public void add(int value) {
		mixValue(value);
		charge(1);
	}

	/**
	 * Adds a long without boxing.
	 *
	 * @param value the long identity component mixed as one word and charged one weight unit
	 */
	public void add(long value) {
		mixValue(value);
		charge(1);
	}

	/**
	 * Adds nullable text by content and charges its retained characters.
	 *
	 * @param value the text identity component read by content, or null for a distinct absent marker
	 */
	public void add(@Nullable String value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(contentHash(value));
			charge(textWeight(value.length()));
		}
	}

	/**
	 * Adds an enum by ordinal with a distinct null marker.
	 *
	 * @param value the ordinal identity component, or null for the distinct zero marker
	 */
	public void add(@Nullable Enum<?> value) {
		mixValue(value == null ? 0 : value.ordinal() + 1L);
		charge(1);
	}

	/**
	 * Hashes locale component and extension text without concatenation or iterator allocation.
	 *
	 * @param value the locale whose language, script, country, variant and extension text is hashed
	 * @return a content hash incorporating the locale components and all present letter-keyed extensions
	 */
	static long localeHash(@Nonnull Locale value) {
		long hash = mix(contentHash(value.getLanguage()), contentHash(value.getScript()));
		hash = mix(hash, contentHash(value.getCountry()));
		hash = mix(hash, contentHash(value.getVariant()));
		for (char key = 'a'; key <= 'z'; key++) {
			final String extension = value.getExtension(key);
			if (extension != null) {
				hash = mix(mix(hash, key), contentHash(extension));
			}
		}
		return hash;
	}

	/**
	 * Measures delimited language, script, country, variant and extension text without building it.
	 *
	 * @param value the locale whose component and extension lengths determine retained text weight
	 * @return the four component lengths plus three delimiters and each letter-keyed extension with two delimiters
	 */
	static int localeTextLength(@Nonnull Locale value) {
		int length = value.getLanguage().length() + value.getScript().length() +
			value.getCountry().length() + value.getVariant().length() + 3;
		for (char key = 'a'; key <= 'z'; key++) {
			final String extension = value.getExtension(key);
			if (extension != null) {
				length += 2 + extension.length();
			}
		}
		return length;
	}

	/**
	 * Adds delimited locale component text, including variants and extensions, without concatenating it.
	 *
	 * @param value the locale whose component and extension text is read, or null for an absent marker
	 */
	public void add(@Nullable Locale value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(localeHash(value));
			charge(textWeight(localeTextLength(value)));
		}
	}

	/**
	 * Adds currency code text.
	 *
	 * @param value the currency whose code contributes to identity, or null for an absent text marker
	 */
	public void add(@Nullable Currency value) {
		add(value == null ? null : value.getCurrencyCode());
	}

	/**
	 * Adds the instant, nanoseconds and offset, preserving OffsetDateTime equality.
	 *
	 * @param value the timestamp whose instant and offset contribute to identity, or null for an absent marker
	 */
	public void add(@Nullable OffsetDateTime value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.toEpochSecond());
			mixValue(value.getNano());
			mixValue(value.getOffset().getTotalSeconds());
			charge(1);
		}
	}

	/**
	 * Adds the attribute name and locale.
	 *
	 * @param value the attribute name and locale contributing to identity, or null for an absent marker
	 */
	public void add(@Nullable AttributeKey value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			add(value.attributeName());
			add(value.locale());
		}
	}

	/**
	 * Adds the value components of a price index key.
	 *
	 * @param value the price-list, currency and record-handling identity, or null for an absent marker
	 */
	public void add(@Nullable PriceIndexKey value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			add(value.getPriceList());
			add(value.getCurrency());
			add(value.getRecordHandling());
		}
	}

	/**
	 * Adds validity and ordered target price index identities.
	 *
	 * @param value the validity instant and ordered target-index identities, or null for an absent marker
	 */
	public void add(@Nullable PriceEvaluationContext value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			add(value.validIn());
			final List<PriceIndexKey> indexes = value.targetPriceIndexes();
			mixValue(indexes.size());
			for (int i = 0; i < indexes.size(); i++) {
				add(indexes.get(i));
			}
		}
	}

	/**
	 * Adds a sealed requested threshold condition.
	 *
	 * @param value the comparison or range components to describe, or null for an absent-condition marker
	 */
	public void add(@Nullable RequestedThresholdPredicate value) {
		if (value == null) {
			mixValue(0);
		} else if (value instanceof final ThresholdComparison comparison) {
			mixValue(1);
			add(comparison.operator());
			addDecimal(comparison.value());
		} else if (value instanceof final ThresholdRange range) {
			mixValue(2);
			addDecimal(range.from());
			addDecimal(range.to());
		} else {
			throw new IllegalArgumentException("Unsupported threshold condition.");
		}
	}

	/**
	 * Adds normalized decimal scale and unscaled magnitude; charges the magnitude's bit length.
	 *
	 * @param value the decimal whose scale and signed unscaled value are hashed as supplied, or null for an
	 *              absent marker
	 */
	void addDecimal(@Nullable BigDecimal value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			final BigInteger unscaled = value.unscaledValue();
			mixValue(decimalHash(unscaled, value.scale()));
			charge(decimalWeight(unscaled));
		}
	}

	/**
	 * Hashes scale and the complete signed magnitude; long-sized magnitudes need no temporary array.
	 *
	 * @param unscaled the complete signed unscaled decimal value to hash without truncating large values
	 * @param scale the decimal scale combined with the unscaled value; may be negative
	 * @return the hash combining the supplied scale and complete signed unscaled value
	 */
	static long decimalHash(@Nonnull BigInteger unscaled, int scale) {
		final long magnitudeHash = unscaled.bitLength() <= 63 ? mix(0, unscaled.longValue()) :
			CONTENT_HASH.hashBytes(unscaled.toByteArray());
		return mix(mix(0, scale), magnitudeHash);
	}

	/**
	 * Adds a nested value record through its own description, with no intermediate accumulator.
	 *
	 * @param value the nested record described directly into this accumulator, or null for an absent marker
	 */
	public void add(@Nullable IdentityRecord value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.classId());
			value.describeTo(this);
		}
	}

	/**
	 * Adds an operand token and propagates opacity.
	 *
	 * @param value the detached operand token contributing hash, weight and opacity, or null for an absent marker
	 */
	public void add(@Nullable IdentityToken value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.hash64());
			charge(value.weight());
			this.opaque |= value.isOpaque();
			this.queryLocal |= value.isOpaque();
		}
	}

	/**
	 * Adds an immutable operand list and its admission classification.
	 *
	 * @param value the immutable operand list contributing hash, weight and locality, or null for an absent marker
	 */
	public void add(@Nullable TokenList value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.hash64());
			charge(value.weight());
			this.opaque |= value.isOpaque();
			this.queryLocal |= value.isQueryLocal();
		}
	}

	/**
	 * Adds immutable bucket values and their admission classification.
	 *
	 * @param value the immutable bucket-value list contributing hash, weight and locality, or null for an absent marker
	 */
	public void add(@Nullable ValueList value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.hash64());
			charge(value.weight());
			this.opaque |= value.isOpaque();
			this.queryLocal |= value.isQueryLocal();
		}
	}

	/**
	 * Adds a side key; an opaque root makes the owning record opaque.
	 *
	 * @param value the side key contributing hash, weight and admission flags, or null for an absent marker
	 */
	public void add(@Nullable FormulaKey value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			this.sideKeys = true;
			this.opaque |= value.isOpaque();
			addChild(value);
		}
	}

	/**
	 * Adds a formula child without propagating its own opacity to this record.
	 *
	 * @param value the child key contributing hash, weight, locality and side-key flags without own-record opacity
	 */
	void addChild(@Nonnull FormulaKey value) {
		mixValue(value.getHash64());
		charge(value.getWeight());
		this.queryLocal |= value.isQueryLocal();
		this.sideKeys |= value.hasSideKeys();
	}

	/**
	 * Adds ordered side keys, preserving duplicates.
	 *
	 * @param values the ordered side keys to read without retaining the list; duplicates contribute separately
	 */
	public void addKeys(@Nonnull List<FormulaKey> values) {
		mixValue(values.size());
		for (int i = 0; i < values.size(); i++) {
			add(values.get(i));
		}
	}

	/**
	 * Adds nullable ordered strings by content.
	 *
	 * @param values the ordered text components to read without retaining the list, or null for an absent-list marker
	 */
	public void addStrings(@Nullable List<String> values) {
		mixValue(values == null ? 0 : 1);
		if (values != null) {
			mixValue(values.size());
			for (int i = 0; i < values.size(); i++) {
				add(values.get(i));
			}
		}
	}

	/**
	 * Returns the completed structural hash.
	 *
	 * @return the structural hash accumulated so far, which does not replace value equality
	 */
	long hash64() {
		return this.hash;
	}

	/**
	 * Returns retained weight.
	 *
	 * @return retained weight units including the owning key, capped at {@link Integer#MAX_VALUE}
	 */
	int weight() {
		return this.weight;
	}

	/**
	 * Returns own-record opacity.
	 *
	 * @return true when a described own-record component is opaque; child opacity alone does not set this flag
	 */
	boolean isOpaque() {
		return this.opaque;
	}

	/**
	 * Returns aggregate locality.
	 *
	 * @return true when any described component or child refuses cross-query reuse
	 */
	boolean isQueryLocal() {
		return this.queryLocal;
	}

	/**
	 * Returns whether a side key was encountered.
	 *
	 * @return true when a side key was added directly or encountered transitively through a child
	 */
	boolean hasSideKeys() {
		return this.sideKeys;
	}
}
