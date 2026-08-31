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

package io.evitadb.index.bPlusTree;

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * WTF-8 codec for {@link FrontCodedStringColumn}'s key blob — UTF-8 extended with the one thing UTF-8 cannot encode,
 * an **unpaired UTF-16 surrogate**.
 *
 * ## Why the column cannot simply use UTF-8
 *
 * A Java {@link String} is a sequence of UTF-16 *code units*, not of Unicode scalar values, so a lone surrogate in
 * `0xD800-0xDFFF` — half of a surrogate pair whose other half is missing — is a perfectly legal {@link String} that
 * no Java-level validation rejects. It is not text: it is what remains when something upstream cut a UTF-16 string
 * through the middle of a supplementary character (a fixed-width column, a naive `substring`, a broken importer).
 *
 * UTF-8 has no representation for one, and {@link String#getBytes(java.nio.charset.Charset)} does not say so — it
 * silently substitutes the byte `0x3F`, the ASCII `'?'`:
 *
 * ```
 * "a\uD800c".getBytes(UTF_8)        →  [97, 63, 99]
 * new String([97, 63, 99], UTF_8)   →  "a?c"            // a DIFFERENT string
 * ```
 *
 * A column that stored its keys that way would be a lossy container, which is not something the B+ tree above it can
 * tolerate: the value written and the value read back must be the same object graph, or a lookup with the original
 * value misses the bucket that was just created for it.
 *
 * ## The encoding
 *
 * WTF-8 is UTF-8 applied to the code-point range `0x0000-0x10FFFF` **including** the surrogate range, so:
 *
 * - a lone surrogate becomes its own three-byte sequence (`U+D800` → `ED A0 80`);
 * - a well-formed surrogate PAIR is still combined into the single four-byte supplementary form (`U+1F600` →
 *   `F0 9F 98 80`), never two three-byte halves — that is what separates WTF-8 from CESU-8, and it is what keeps
 *   the column's supplementary-lead-byte threshold meaningful;
 * - **every other string encodes to exactly the bytes UTF-8 would produce.**
 *
 * That last property is the reason this codec is affordable at all. The column's blob size, its prefix sharing, its
 * restart-point spacing and its byte-compare fast path are all defined over the encoded bytes, and none of them
 * moves for any input UTF-8 could already represent.
 *
 * ## Ordering — why a lone surrogate stays eligible for the byte-compare fast path
 *
 * {@link FrontCodedStringColumn}'s fast path compares raw bytes and must agree with {@link String#compareTo}, which
 * compares UTF-16 code units. The two disagree for supplementary characters, which is why the column excludes any
 * byte `>= 0xF0` from the fast path (see {@code FrontCodedStringColumn#isBmpSafe(byte[], int, int)}).
 *
 * A WTF-8 lone surrogate encodes below that threshold (`ED ...`) and therefore stays on the fast path — correctly,
 * because as long as **both** operands consist only of code points at or below `U+FFFF`, UTF-8 byte order **is**
 * code-point order, which **is** UTF-16 code-unit order:
 *
 * ```
 * "a\uD800c" vs "a\uE000"   String.compareTo: D800 < E000        → first
 *                           WTF-8 bytes     : ED A0 80 < EE 80 80 → first   ✓ agree
 * ```
 *
 * The restriction to the BMP is load-bearing rather than decorative, and a lone surrogate does not weaken it — a
 * well-formed PAIR does. A value holding the single code unit `DAFB` sorts AFTER one holding the pair `D99E DC59`
 * under {@link String#compareTo}, which compares those code UNITS, but BEFORE it by bytes, which compare the code
 * POINTS `U+DAFB` and `U+66459`. That is the ordinary UTF-8-versus-UTF-16 disagreement, it is exactly what the
 * `>= 0xF0` threshold exists to exclude, and the pair is the operand that trips it — the lone surrogate never does.
 * Pinned by {@code Wtf8Test.Ordering#shouldExcludeSupplementaryCharactersFromTheAgreement}.
 *
 * Continuation bytes keep their `10xxxxxx` form, so WTF-8 also preserves UTF-8's self-synchronization: a valid UTF-8
 * byte pattern can never match spanning into the middle of a surrogate sequence. That is what lets the trigram
 * byte-containment path go on matching UTF-8 patterns against this blob unchanged.
 *
 * ## Fast paths
 *
 * Both directions delegate to the JDK's intrinsified codec unless a surrogate sequence is actually involved, so the
 * hand-written loops below run only for the values that need them:
 *
 * - {@link #encode(String)} encodes through the JDK and only re-encodes by hand when the result betrays a
 *   substitution — see the method's own note on why that test is exact;
 * - decoding is gated by the caller: {@link FrontCodedStringColumn} records once per encode whether the blob holds
 *   any surrogate sequence at all (a byte scan it was performing anyway), and calls {@link #decode} only when it
 *   does. Every other column keeps calling {@code new String(bytes, UTF_8)} directly.
 *
 * The class is public but its codec is not: only {@link #hasUnpairedSurrogate} is exported, because the question
 * "would UTF-8 lose this string?" is asked outside this package too — {@code TrigramSubstringSearch} has to answer it
 * before it may offer a UTF-8 pattern to the byte-containment path. Encoding and decoding stay package-private; the
 * column is their only caller.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class Wtf8 {
	/**
	 * The byte {@link String#getBytes(java.nio.charset.Charset)} substitutes for a character it cannot encode — the
	 * ASCII `'?'`. Its presence in the encoder's output is what triggers the by-hand re-encode.
	 */
	private static final byte SUBSTITUTION_BYTE = (byte) '?';
	/**
	 * Lead byte of every three-byte sequence covering `U+D000-U+FFFF`, and therefore of every encoded surrogate.
	 */
	private static final int SURROGATE_LEAD_BYTE = 0xED;
	/**
	 * Lowest second byte of an encoded surrogate (`U+D800` → `ED A0 80`). Second bytes below this one still belong to
	 * `U+D000-U+D7FF`, which are ordinary BMP characters and not surrogates.
	 */
	private static final int SURROGATE_SECOND_BYTE_FLOOR = 0xA0;

	private Wtf8() {
		throw new UnsupportedOperationException("Utility class - not instantiable.");
	}

	/**
	 * Encodes `value` to WTF-8, which for every string containing no unpaired surrogate is byte-for-byte the UTF-8
	 * encoding produced by {@link String#getBytes(java.nio.charset.Charset)}.
	 *
	 * ## Why the fast-path test is exact rather than merely cheap
	 *
	 * The JDK encoder is used first and its output inspected, rather than scanning `value`'s chars up front, because
	 * the scan can then be skipped for essentially every string in existence. The test is two-stage and the order
	 * matters: `0x3F` in the output is a *necessary* condition for a substitution having occurred (the only byte the
	 * encoder ever substitutes), but not a sufficient one — a value that genuinely contains `'?'` produces it too.
	 * So a `0x3F` byte only triggers the second stage, {@link #hasUnpairedSurrogate}, which answers the question
	 * exactly by looking at the code units themselves.
	 *
	 * The consequence is that the by-hand encoder below runs only for strings that genuinely carry an unpaired
	 * surrogate, and the extra byte scan runs only for strings containing a question mark.
	 *
	 * @param value the string to encode
	 * @return its WTF-8 bytes; a fresh array in every case, safe for the caller to retain
	 */
	@Nonnull
	static byte[] encode(@Nonnull String value) {
		final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
		if (!containsByte(utf8, SUBSTITUTION_BYTE) || !hasUnpairedSurrogate(value)) {
			return utf8;
		}
		return encodeByHand(value);
	}

	/**
	 * Decodes `length` bytes of WTF-8 starting at `offset`, reproducing the exact UTF-16 code-unit sequence
	 * {@link #encode} was given — including any unpaired surrogate, which {@code new String(bytes, UTF_8)} would
	 * instead turn into the replacement character `U+FFFD`.
	 *
	 * Callers that know the range holds no surrogate sequence should not call this at all; see the class javadoc.
	 *
	 * @param bytes  the backing array
	 * @param offset the range's start offset
	 * @param length the range's length in bytes
	 * @return the decoded string
	 */
	@Nonnull
	static String decode(@Nonnull byte[] bytes, int offset, int length) {
		final char[] out = new char[length];
		int outLength = 0;
		int i = offset;
		final int end = offset + length;
		while (i < end) {
			final int lead = bytes[i] & 0xFF;
			if (isEncodedSurrogateAt(bytes, i, end)) {
				// ED A0 80 .. ED BF BF - the one sequence UTF-8 forbids and this codec exists for
				out[outLength++] = (char) (((lead & 0x0F) << 12)
					| ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F));
				i += 3;
			} else {
				// everything else is ordinary UTF-8; hand the longest such run back to the JDK in one call rather
				// than decoding it a code point at a time
				final int runStart = i;
				while (i < end && !isEncodedSurrogateAt(bytes, i, end)) {
					i++;
				}
				final String run = new String(bytes, runStart, i - runStart, StandardCharsets.UTF_8);
				run.getChars(0, run.length(), out, outLength);
				outLength += run.length();
			}
		}
		return new String(out, 0, outLength);
	}

	/**
	 * Answers whether `bytes[offset, offset + length)` contains any encoded surrogate, i.e. whether {@link #decode}
	 * would produce a different answer from {@code new String(bytes, offset, length, UTF_8)}.
	 *
	 * {@link FrontCodedStringColumn} folds this test into the single suffix-byte scan its encode pass already makes,
	 * and caches the answer for the whole blob — so the decode side pays one boolean check rather than a scan.
	 *
	 * @param bytes  the backing array
	 * @param offset the range's start offset
	 * @param length the range's length in bytes
	 * @return {@code true} if the range holds at least one encoded surrogate
	 */
	static boolean containsEncodedSurrogate(@Nonnull byte[] bytes, int offset, int length) {
		final int end = offset + length;
		for (int i = offset; i < end; i++) {
			if (isEncodedSurrogateAt(bytes, i, end)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Answers whether `value` contains a UTF-16 surrogate code unit without its partner — the exact condition under
	 * which UTF-8 encoding is lossy and this codec's by-hand path is required.
	 *
	 * A well-formed pair is stepped over as a unit, so a string made only of proper pairs (an emoji, a supplementary
	 * CJK character) answers {@code false} and takes the JDK path.
	 *
	 * @param value the string to inspect
	 * @return {@code true} if at least one surrogate code unit lacks its partner
	 */
	public static boolean hasUnpairedSurrogate(@Nonnull String value) {
		final int length = value.length();
		for (int i = 0; i < length; i++) {
			final char codeUnit = value.charAt(i);
			if (Character.isHighSurrogate(codeUnit)) {
				if (i + 1 == length || !Character.isLowSurrogate(value.charAt(i + 1))) {
					return true;
				}
				// a well-formed pair, so step over its low half rather than meeting it as a lone low surrogate
				i++;
			} else if (Character.isLowSurrogate(codeUnit)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Encodes `value` a code unit at a time, emitting the three-byte form for an unpaired surrogate and the ordinary
	 * UTF-8 form for everything else. Reached only from {@link #encode}, and only for a value that genuinely carries
	 * an unpaired surrogate.
	 *
	 * @param value the string to encode
	 * @return its WTF-8 bytes
	 */
	@Nonnull
	private static byte[] encodeByHand(@Nonnull String value) {
		final int length = value.length();
		// a code unit never exceeds three bytes on its own, and a pair (two code units) takes four - so three bytes
		// per code unit is an exact upper bound, trimmed once at the end
		final byte[] out = new byte[length * 3];
		int outLength = 0;
		for (int i = 0; i < length; i++) {
			final char codeUnit = value.charAt(i);
			final int codePoint;
			if (Character.isHighSurrogate(codeUnit) && i + 1 < length
				&& Character.isLowSurrogate(value.charAt(i + 1))) {
				// a well-formed pair keeps the four-byte supplementary form - this is what makes the encoding WTF-8
				// rather than CESU-8, and it is what keeps the column's `>= 0xF0` supplementary threshold exact
				codePoint = Character.toCodePoint(codeUnit, value.charAt(i + 1));
				i++;
			} else {
				codePoint = codeUnit;
			}
			if (codePoint < 0x80) {
				out[outLength++] = (byte) codePoint;
			} else if (codePoint < 0x800) {
				out[outLength++] = (byte) (0xC0 | (codePoint >> 6));
				out[outLength++] = (byte) (0x80 | (codePoint & 0x3F));
			} else if (codePoint < 0x10000) {
				// the surrogate range lands here, which is precisely the case UTF-8 refuses and WTF-8 admits
				out[outLength++] = (byte) (0xE0 | (codePoint >> 12));
				out[outLength++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
				out[outLength++] = (byte) (0x80 | (codePoint & 0x3F));
			} else if (codePoint <= Character.MAX_CODE_POINT) {
				out[outLength++] = (byte) (0xF0 | (codePoint >> 18));
				out[outLength++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
				out[outLength++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
				out[outLength++] = (byte) (0x80 | (codePoint & 0x3F));
			} else {
				throw new GenericEvitaInternalError(
					"Code point `" + codePoint + "` is above Unicode's maximum - a Java String cannot produce one, " +
						"so reaching this branch means the code unit walk above is broken."
				);
			}
		}
		return Arrays.copyOf(out, outLength);
	}

	/**
	 * Answers whether a three-byte encoded surrogate starts at `index`. The second byte is what distinguishes one
	 * from the ordinary BMP characters `U+D000-U+D7FF`, which share the `0xED` lead byte but stay below
	 * {@link #SURROGATE_SECOND_BYTE_FLOOR}.
	 *
	 * @param bytes the backing array
	 * @param index the candidate start offset
	 * @param end   the exclusive end of the readable range
	 * @return {@code true} if `bytes[index, index + 3)` is an encoded surrogate
	 */
	private static boolean isEncodedSurrogateAt(@Nonnull byte[] bytes, int index, int end) {
		return index + 2 < end
			&& (bytes[index] & 0xFF) == SURROGATE_LEAD_BYTE
			&& (bytes[index + 1] & 0xFF) >= SURROGATE_SECOND_BYTE_FLOOR;
	}

	/**
	 * Answers whether `bytes` holds `needle` anywhere.
	 *
	 * @param bytes  the array to scan
	 * @param needle the byte looked for
	 * @return {@code true} if present
	 */
	private static boolean containsByte(@Nonnull byte[] bytes, byte needle) {
		for (final byte candidate : bytes) {
			if (candidate == needle) {
				return true;
			}
		}
		return false;
	}

}
