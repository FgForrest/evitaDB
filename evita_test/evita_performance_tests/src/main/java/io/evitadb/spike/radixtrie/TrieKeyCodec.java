/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.spike.radixtrie;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * Converts an attribute value into an **order-preserving** {@code byte[]} key suitable for a byte-keyed
 * radix trie. The contract is: for any two values {@code a} and {@code b},
 * {@code compare(a, b)} has the same sign as the unsigned lexicographic comparison of
 * {@code encode(a)} and {@code encode(b)}. This is what lets the trie answer ordered range queries by
 * comparing encoded byte bounds.
 *
 * Three codecs back the radix-trie memory spike (see the package-level overview):
 *
 * 1. {@link #utf8String()} — natural-order (no-locale) String keys. UTF-8 byte order coincides with
 *    Unicode code-point order, so it is order-preserving for the {@code Comparator.naturalOrder()} the
 *    inverted index uses when no locale is supplied. Compact, so prefixes share maximally.
 * 2. {@link #collationKeyString(Locale)} — localized String keys. Keys on
 *    {@link java.text.CollationKey#toByteArray()}, which is purpose-built to be order-preserving under the
 *    locale {@link Collator} that the inverted index uses when a locale **is** supplied. Collation keys
 *    are larger than the raw UTF-8 bytes, so prefix sharing is less dramatic — this is the honest,
 *    realistic localized case.
 * 3. {@link #temporal()} — {@link OffsetDateTime}/{@link Instant} keys, mirroring the inverted index which
 *    normalizes {@code OffsetDateTime} to a UTC {@link Instant}. Encodes a sign-flipped big-endian
 *    epoch-second followed by a big-endian nano-of-second so the pre/post-1970 ordering is correct (see
 *    the signed-long trap below).
 *
 * ## ⚠️ The signed-long trap
 *
 * Epoch values are signed: {@code 1969-12-31} is a negative long whose most-significant bit is set, while
 * {@code 1970-01-01} is {@code 0}. Compared as raw big-endian bytes (unsigned lexicographic), every
 * negative timestamp would sort **after** every positive one — the trie would believe 1969 came after
 * 2026 and return nonsense for range queries. Flipping the sign bit ({@code value ^ Long.MIN_VALUE})
 * shifts the whole axis so that {@code Long.MIN_VALUE} maps to {@code 0x00..} and {@code Long.MAX_VALUE}
 * maps to {@code 0xFF..}; unsigned byte order then matches signed numeric order exactly.
 *
 * @author Claude (radix-trie memory spike), FG Forrest a.s. (c) 2026
 */
public interface TrieKeyCodec<T> {

	/**
	 * Encodes the given value into an order-preserving byte sequence. Must never return {@code null}.
	 *
	 * @param value the value to encode (never {@code null})
	 * @return an order-preserving byte representation
	 */
	@Nonnull
	byte[] encode(@Nonnull T value);

	/**
	 * @return a codec for natural-order (no-locale) String keys backed by raw UTF-8 bytes.
	 */
	@Nonnull
	static TrieKeyCodec<String> utf8String() {
		return Utf8StringCodec.INSTANCE;
	}

	/**
	 * @param locale the locale whose collation order the encoding must preserve
	 * @return a codec for localized String keys backed by {@link java.text.CollationKey} bytes
	 */
	@Nonnull
	static TrieKeyCodec<String> collationKeyString(@Nonnull Locale locale) {
		return new CollationKeyStringCodec(locale);
	}

	/**
	 * @return a codec for {@link OffsetDateTime} keys (normalized to a UTC {@link Instant}).
	 */
	@Nonnull
	static TrieKeyCodec<OffsetDateTime> temporal() {
		return TemporalCodec.INSTANCE;
	}

	/* ============================================================================================ */

	/**
	 * UTF-8 encoding of a String. Order-preserving because UTF-8 lexicographic byte order equals Unicode
	 * code-point order, which is what {@code String.compareTo} (and {@code Comparator.naturalOrder()})
	 * yields for the basic-multilingual-plane comparison evitaDB performs without a locale.
	 */
	final class Utf8StringCodec implements TrieKeyCodec<String> {
		static final Utf8StringCodec INSTANCE = new Utf8StringCodec();

		@Nonnull
		@Override
		public byte[] encode(@Nonnull String value) {
			return value.getBytes(StandardCharsets.UTF_8);
		}
	}

	/**
	 * Collation-key encoding of a String for a fixed {@link Locale}. The {@link Collator} is configured for
	 * full strength so that the produced {@link java.text.CollationKey} byte arrays reproduce the same total
	 * order evitaDB's {@code LocalizedStringComparator} imposes. {@code Collator} is not thread-safe, hence
	 * a fresh clone is taken per encode in this single-threaded spike; the production codec would pool them.
	 */
	final class CollationKeyStringCodec implements TrieKeyCodec<String> {
		private final Collator collator;

		CollationKeyStringCodec(@Nonnull Locale locale) {
			this.collator = Collator.getInstance(locale);
			this.collator.setStrength(Collator.TERTIARY);
		}

		@Nonnull
		@Override
		public byte[] encode(@Nonnull String value) {
			// Collator is stateful/non-thread-safe; the spike measurement loop is single-threaded so a shared
			// instance is fine. toByteArray() yields an order-preserving, NUL-terminated key.
			return this.collator.getCollationKey(value).toByteArray();
		}
	}

	/**
	 * Encodes an {@link OffsetDateTime} as a 12-byte fixed-width key: 8 bytes of sign-flipped big-endian
	 * epoch-second followed by 4 bytes of big-endian nano-of-second. The sign flip ({@code ^ MIN_VALUE})
	 * fixes the signed-long ordering trap documented on the interface. Temporally clustered values share
	 * their leading (year/month/day/hour) bytes, which is the prefix-compression opportunity.
	 */
	final class TemporalCodec implements TrieKeyCodec<OffsetDateTime> {
		static final TemporalCodec INSTANCE = new TemporalCodec();

		@Nonnull
		@Override
		public byte[] encode(@Nonnull OffsetDateTime value) {
			final Instant instant = value.toInstant();
			final long shiftedSeconds = instant.getEpochSecond() ^ Long.MIN_VALUE;
			final int nano = instant.getNano();
			final byte[] out = new byte[12];
			out[0] = (byte) (shiftedSeconds >>> 56);
			out[1] = (byte) (shiftedSeconds >>> 48);
			out[2] = (byte) (shiftedSeconds >>> 40);
			out[3] = (byte) (shiftedSeconds >>> 32);
			out[4] = (byte) (shiftedSeconds >>> 24);
			out[5] = (byte) (shiftedSeconds >>> 16);
			out[6] = (byte) (shiftedSeconds >>> 8);
			out[7] = (byte) shiftedSeconds;
			out[8] = (byte) (nano >>> 24);
			out[9] = (byte) (nano >>> 16);
			out[10] = (byte) (nano >>> 8);
			out[11] = (byte) nano;
			return out;
		}
	}
}
