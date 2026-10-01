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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * The key encoding of the fulltext term dictionary: one key per (field, term), rendered as a fixed-width hexadecimal
 * field prefix followed by the analyzed term.
 *
 * ## Why one dictionary for all fields, and why this prefix
 *
 * All searchable fields of one (collection, locale) share a single term dictionary, so a term that occurs in three
 * fields costs three keys in one tree rather than three trees. The prefix keeps the fields apart, and its shape is
 * what makes that safe:
 *
 * - **Fixed width** makes the key order exactly (field, term). A variable-width prefix would interleave field `1`'s
 *   terms with field `10`'s, and a prefix expansion would no longer be a single contiguous cursor walk.
 * - **Lower-case hexadecimal ASCII** sorts in numeric order under the natural `String` (UTF-16 code-unit) order the
 *   dictionary uses (`'0'..'9'` precede `'a'..'f'`), and keeps every prefix character outside the surrogate range,
 *   which the front-coded string column's byte-compare fast path assumes. A packed `char` per field would be shorter
 *   but would reach the surrogates past field 55,295.
 *
 * Four digits address 65,536 fields per dictionary, far beyond any realistic schema; the limit is checked rather than
 * assumed.
 *
 * The encoding is shared by the write path, the query path and (later) the persisted form, so it lives in one place.
 * The measured campaign ran on exactly this shape (`p1-index-core-measurements.md`, the "field prefix width 4" runs).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FulltextTermKeys {

	/**
	 * Number of hexadecimal digits of the field prefix.
	 */
	public static final int FIELD_PREFIX_WIDTH = 4;

	/**
	 * Highest field id the prefix can address.
	 */
	public static final int MAX_FIELD_ID = (1 << (4 * FIELD_PREFIX_WIDTH)) - 1;

	/**
	 * Lower-case hexadecimal digits, indexed by their value.
	 */
	private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

	private FulltextTermKeys() {
		// utility class
	}

	/**
	 * Renders the fixed-width prefix of a field. Every key of the field starts with it, so it is also the lower bound
	 * of a walk over all terms of the field.
	 *
	 * @param fieldId id of the field, `0` to {@link #MAX_FIELD_ID}
	 * @return the prefix, exactly {@link #FIELD_PREFIX_WIDTH} characters long
	 * @throws GenericEvitaInternalError when the id cannot be encoded
	 */
	@Nonnull
	public static String fieldPrefix(int fieldId) {
		assertFieldIdEncodable(fieldId);
		final char[] prefix = new char[FIELD_PREFIX_WIDTH];
		writePrefix(fieldId, prefix);
		return new String(prefix);
	}

	/**
	 * Encodes a (field, term) pair into its dictionary key.
	 *
	 * @param fieldId id of the field, `0` to {@link #MAX_FIELD_ID}
	 * @param term    the analyzed term; may be empty, in which case the key equals the field prefix
	 * @return the dictionary key
	 * @throws GenericEvitaInternalError when the field id cannot be encoded
	 */
	@Nonnull
	public static String encode(int fieldId, @Nonnull String term) {
		assertFieldIdEncodable(fieldId);
		final char[] key = new char[FIELD_PREFIX_WIDTH + term.length()];
		writePrefix(fieldId, key);
		term.getChars(0, term.length(), key, FIELD_PREFIX_WIDTH);
		return new String(key);
	}

	/**
	 * Decodes the field id of a dictionary key.
	 *
	 * @param key a key produced by {@link #encode(int, String)}
	 * @return the field id the key belongs to
	 * @throws GenericEvitaInternalError when the key does not start with a well-formed prefix
	 */
	public static int fieldIdOf(@Nonnull String key) {
		assertWellFormed(key);
		int fieldId = 0;
		for (int i = 0; i < FIELD_PREFIX_WIDTH; i++) {
			fieldId = (fieldId << 4) | hexValue(key.charAt(i), key);
		}
		return fieldId;
	}

	/**
	 * Decodes the term of a dictionary key.
	 *
	 * @param key a key produced by {@link #encode(int, String)}
	 * @return the term the key carries
	 * @throws GenericEvitaInternalError when the key does not start with a well-formed prefix
	 */
	@Nonnull
	public static String termOf(@Nonnull String key) {
		assertWellFormed(key);
		return key.substring(FIELD_PREFIX_WIDTH);
	}

	/**
	 * Returns whether a dictionary key belongs to the passed field. Cheaper than {@link #fieldIdOf(String)} on a walk
	 * that only needs to know where the field's run of keys ends, because it compares the prefix characters directly.
	 *
	 * @param key    a key produced by {@link #encode(int, String)}
	 * @param prefix the field prefix produced by {@link #fieldPrefix(int)}
	 * @return true when the key starts with the prefix
	 */
	public static boolean belongsTo(@Nonnull String key, @Nonnull String prefix) {
		return key.startsWith(prefix);
	}

	/**
	 * Writes the hexadecimal digits of the field id into the first {@link #FIELD_PREFIX_WIDTH} slots of the target.
	 *
	 * @param fieldId an encodable field id
	 * @param target  array of at least {@link #FIELD_PREFIX_WIDTH} characters
	 */
	private static void writePrefix(int fieldId, @Nonnull char[] target) {
		int remaining = fieldId;
		for (int i = FIELD_PREFIX_WIDTH - 1; i >= 0; i--) {
			target[i] = HEX_DIGITS[remaining & 0xF];
			remaining >>>= 4;
		}
	}

	/**
	 * Converts one lower-case hexadecimal digit to its value.
	 *
	 * @param digit the character to convert
	 * @param key   the key it was read from, for the error message
	 * @return the digit's value, `0` to `15`
	 * @throws GenericEvitaInternalError when the character is not a lower-case hexadecimal digit
	 */
	private static int hexValue(char digit, @Nonnull String key) {
		if (digit >= '0' && digit <= '9') {
			return digit - '0';
		} else if (digit >= 'a' && digit <= 'f') {
			return digit - 'a' + 10;
		} else {
			throw new GenericEvitaInternalError(
				"Fulltext dictionary key `" + key + "` does not start with a lower-case hexadecimal field prefix!"
			);
		}
	}

	/**
	 * Verifies the field id fits the prefix.
	 *
	 * @param fieldId the id to verify
	 * @throws GenericEvitaInternalError when it does not
	 */
	private static void assertFieldIdEncodable(int fieldId) {
		Assert.isPremiseValid(
			fieldId >= 0 && fieldId <= MAX_FIELD_ID,
			() -> "Fulltext field id " + fieldId + " does not fit into the " + FIELD_PREFIX_WIDTH +
				"-digit field prefix (0 to " + MAX_FIELD_ID + ")!"
		);
	}

	/**
	 * Verifies the key is long enough to carry a prefix.
	 *
	 * @param key the key to verify
	 * @throws GenericEvitaInternalError when it is not
	 */
	private static void assertWellFormed(@Nonnull String key) {
		Assert.isPremiseValid(
			key.length() >= FIELD_PREFIX_WIDTH,
			() -> "Fulltext dictionary key `" + key + "` is shorter than the " + FIELD_PREFIX_WIDTH +
				"-character field prefix!"
		);
	}

}
