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
import net.openhft.hashing.LongHashFunction;
import net.openhft.hashing.Access;
import java.nio.ByteOrder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Single-pass structural hash, retained weight, opacity and locality description.
 * Primitive overloads avoid boxing and each nested value feeds this same accumulator.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class KeyAccumulator {
	/** Content hash independent of the legacy formula hash machinery. */
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

	/** Seeds the description with the declaring class identifier. */
	public KeyAccumulator(long classId) {
		this.hash = mix(0, classId);
	}
	/** Combines one primitive word with an avalanche mixer. */
	public static long mix(long seed, long value) {
		long mixed = value + 0x9e3779b97f4a7c15L;
		mixed = (mixed ^ (mixed >>> 33)) * 0xff51afd7ed558ccdL;
		mixed = (mixed ^ (mixed >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return Long.rotateLeft(seed, 27) * 0x9e3779b97f4a7c15L ^ mixed ^ (mixed >>> 33);
	}
	/** Hashes every character without allocating a byte array or using String.hashCode. */
	public static long contentHash(@Nonnull CharSequence text) {
		return CONTENT_HASH.hash(text, CHAR_ACCESS, 0, (long) text.length() * Character.BYTES);
	}
	/** Adds weights with saturation rather than overflow. */
	static int sumWeight(int left, int right) {
		return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
	}
	/** Mixes a word without charging it as a separately retained component. */
	void mixValue(long value) {
		this.hash = mix(this.hash, value);
	}
	/** Adds retained weight. */
	private void charge(int value) {
		this.weight = sumWeight(this.weight, value);
	}
	/** Adds a boolean without boxing. */
	public void add(boolean value) {
		mixValue(value ? 1 : 0);
		charge(1);
	}
	/** Adds an integer without boxing. */
	public void add(int value) {
		mixValue(value);
		charge(1);
	}
	/** Adds a long without boxing. */
	public void add(long value) {
		mixValue(value);
		charge(1);
	}
	/** Adds nullable text by content and charges its retained characters. */
	public void add(@Nullable String value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(contentHash(value));
			charge(1 + value.length() / 16);
		}
	}
	/** Adds an enum by ordinal with a distinct null marker. */
	public void add(@Nullable Enum<?> value) {
		mixValue(value == null ? 0 : value.ordinal() + 1L);
		charge(1);
	}
	/** Hashes locale component and extension text without concatenation or iterator allocation. */
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
	/** Measures delimited language, script, country, variant and extension text without building it. */
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
	/** Adds delimited locale component text, including variants and extensions, without concatenating it. */
	public void add(@Nullable Locale value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(localeHash(value));
			charge(1 + localeTextLength(value) / 16);
		}
	}
	/** Adds currency code text. */
	public void add(@Nullable Currency value) {
		add(value == null ? null : value.getCurrencyCode());
	}
	/** Adds the instant, nanoseconds and offset, preserving OffsetDateTime equality. */
	public void add(@Nullable OffsetDateTime value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.toEpochSecond());
			mixValue(value.getNano());
			mixValue(value.getOffset().getTotalSeconds());
			charge(1);
		}
	}
	/** Adds the attribute name and locale. */
	public void add(@Nullable AttributeKey value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			add(value.attributeName());
			add(value.locale());
		}
	}
	/** Adds the value components of a price index key. */
	public void add(@Nullable PriceIndexKey value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			add(value.getPriceList());
			add(value.getCurrency());
			add(value.getRecordHandling());
		}
	}
	/** Adds validity and ordered target price index identities. */
	public void add(@Nullable PriceEvaluationContext value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			add(value.validIn());
			mixValue(value.targetPriceIndexes().size());
			for (int i = 0; i < value.targetPriceIndexes().size(); i++) {
				add(value.targetPriceIndexes().get(i));
			}
		}
	}
	/** Adds a sealed requested threshold condition. */
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
	/** Adds normalized decimal scale and unscaled magnitude; charges the magnitude's bit length. */
	void addDecimal(@Nullable BigDecimal value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			final BigInteger unscaled = value.unscaledValue();
			mixValue(decimalHash(unscaled, value.scale()));
			charge(1 + unscaled.bitLength() / 64);
		}
	}
	/** Hashes scale and the complete signed magnitude; long-sized magnitudes need no temporary array. */
	static long decimalHash(@Nonnull BigInteger unscaled, int scale) {
		final long magnitudeHash = unscaled.bitLength() <= 63 ? mix(0, unscaled.longValue()) :
			CONTENT_HASH.hashBytes(unscaled.toByteArray());
		return mix(mix(0, scale), magnitudeHash);
	}
	/** Adds a nested value record through its own description, with no intermediate accumulator. */
	public void add(@Nullable IdentityRecord value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.classId());
			value.describeTo(this);
		}
	}
	/** Adds an operand token and propagates opacity. */
	public void add(@Nullable IdentityToken value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.hash64());
			charge(value.weight());
			this.opaque |= value.isOpaque();
			this.queryLocal |= value.isOpaque();
		}
	}
	/** Adds an immutable operand list and its admission classification. */
	public void add(@Nullable TokenList value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.hash64());
			charge(value.weight());
			this.opaque |= value.isOpaque();
			this.queryLocal |= value.isQueryLocal();
		}
	}
	/** Adds immutable bucket values and their admission classification. */
	public void add(@Nullable ValueList value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			mixValue(value.hash64());
			charge(value.weight());
			this.opaque |= value.isOpaque();
			this.queryLocal |= value.isQueryLocal();
		}
	}
	/** Adds a side key; an opaque root makes the owning record opaque. */
	public void add(@Nullable FormulaKey value) {
		mixValue(value == null ? 0 : 1);
		if (value != null) {
			this.sideKeys = true;
			this.opaque |= value.isOpaque();
			addChild(value);
		}
	}
	/** Adds a formula child without propagating its own opacity to this record. */
	void addChild(@Nonnull FormulaKey value) {
		mixValue(value.getHash64());
		charge(value.getWeight());
		this.queryLocal |= value.isQueryLocal();
		this.sideKeys |= value.hasSideKeys();
	}
	/** Adds ordered side keys, preserving duplicates. */
	public void addKeys(@Nonnull List<FormulaKey> values) {
		mixValue(values.size());
		for (int i = 0; i < values.size(); i++) {
			add(values.get(i));
		}
	}
	/** Adds nullable ordered strings by content. */
	public void addStrings(@Nullable List<String> values) {
		mixValue(values == null ? 0 : 1);
		if (values != null) {
			mixValue(values.size());
			for (int i = 0; i < values.size(); i++) {
				add(values.get(i));
			}
		}
	}
	/** Returns the completed structural hash. */
	long hash64() {
		return this.hash;
	}
	/** Returns retained weight. */
	int weight() {
		return this.weight;
	}
	/** Returns own-record opacity. */
	boolean isOpaque() {
		return this.opaque;
	}
	/** Returns aggregate locality. */
	boolean isQueryLocal() {
		return this.queryLocal;
	}
	/** Returns whether a side key was encountered. */
	boolean hasSideKeys() {
		return this.sideKeys;
	}
}
