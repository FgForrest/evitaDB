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

package io.evitadb.externalApi.http.routing;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Encoded request-target bounds and once-only UTF-8 decoding shared by HTTP and WebSocket routing.
 * Delimiters are found before decoding: escaped slash and question mark are segment data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RoutingPath {
	/** Prevents utility instantiation. */
	private RoutingPath() {
	}

	/**
	 * Scans only through the first real query separator, recording escapes and trimming trailing slashes by bounds.
	 * Returns the end shifted left one bit with the escape flag in bit zero, or -1 when an escape before the query is
	 * malformed or not strict UTF-8. Packing avoids a request allocation; ASCII needs one pass, escaped input
	 * additionally validates from its first `%`.
	 */
	static long scan(@Nonnull String target) {
		int end = 0;
		int firstEscape = -1;
		for (int position = 0; position < target.length(); position++) {
			final char character = target.charAt(position);
			if (character == '?') {
				break;
			}
			if (character != '/' || position == 0) {
				end = position + 1;
			}
			if (character == '%' && firstEscape < 0) {
				firstEscape = position;
			}
		}
		if (firstEscape >= 0 && !valid(target, firstEscape, end)) {
			return -1;
		}
		return ((long) end << 1) | (firstEscape < 0 ? 0 : 1);
	}

	/**
	 * Finds the next raw slash and its segment's escape flag in the same bounded pass, using the scan's packing.
	 * When `pathEscaped` is false the `%` test is skipped and the flag stays clear.
	 */
	static long segment(@Nonnull String path, int start, int limit, boolean pathEscaped) {
		int end = start;
		boolean escaped = false;
		while (end < limit && path.charAt(end) != '/') {
			escaped |= pathEscaped && path.charAt(end) == '%';
			end++;
		}
		return ((long) end << 1) | (escaped ? 1 : 0);
	}

	/** Normalizes registration text only; decoded literal keys retain their spelling inside the path. */
	@Nonnull
	static String registration(@Nonnull String path) {
		int end = path.length();
		while (end > 1 && path.charAt(end - 1) == '/') {
			end--;
		}
		final String trimmed = path.substring(0, end);
		return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
	}

	/**
	 * Splits registration text with a leading slash into its segments at registration time; `/` has none, and empty
	 * segments, trailing ones included, are retained.
	 */
	@Nonnull
	static String[] splitSegments(@Nonnull String path) {
		return path.equals("/") ? new String[0] : path.substring(1).split("/", -1);
	}

	/** Validates from the known first escape without allocation, including UTF-8 overlongs and range limits. */
	private static boolean valid(@Nonnull String path, int position, int end) {
		int continuation = 0;
		int minimum = 0x80;
		int maximum = 0xbf;
		while (position < end) {
			if (path.charAt(position) != '%') {
				if (continuation != 0) {
					return false;
				}
				position++;
				continue;
			}
			if (position + 2 >= end) {
				return false;
			}
			final int high = hex(path.charAt(position + 1));
			final int low = hex(path.charAt(position + 2));
			if (high < 0 || low < 0) {
				return false;
			}
			final int value = (high << 4) | low;
			if (continuation > 0) {
				if (value < minimum || value > maximum) {
					return false;
				}
				continuation--;
				minimum = 0x80;
				maximum = 0xbf;
			} else if (value >= 0x80) {
				if (value >= 0xc2 && value <= 0xdf) {
					continuation = 1;
				} else if (value >= 0xe0 && value <= 0xef) {
					continuation = 2;
					minimum = value == 0xe0 ? 0xa0 : 0x80;
					maximum = value == 0xed ? 0x9f : 0xbf;
				} else if (value >= 0xf0 && value <= 0xf4) {
					continuation = 3;
					minimum = value == 0xf0 ? 0x90 : 0x80;
					maximum = value == 0xf4 ? 0x8f : 0xbf;
				} else {
					return false;
				}
			}
			position += 3;
		}
		return continuation == 0;
	}

	/** Accepts only ASCII hexadecimal digits, as required by request-target percent triplets. */
	private static int hex(char character) {
		if (character >= '0' && character <= '9') {
			return character - '0';
		}
		if (character >= 'a' && character <= 'f') {
			return character - 'a' + 10;
		}
		return character >= 'A' && character <= 'F' ? character - 'A' + 10 : -1;
	}

	/** Computes the decoded region's String hash without creating decoded text; the region must be validated. */
	static int decodedHash(@Nonnull String path, int start, int end) {
		int hash = 0;
		int position = start;
		while (position < end) {
			final long next = decodedCharacter(path, position);
			final int character = (int) next;
			position = (int) (next >>> 32);
			if (character > Character.MAX_VALUE) {
				hash = 31 * hash + Character.highSurrogate(character);
				hash = 31 * hash + Character.lowSurrogate(character);
			} else {
				hash = 31 * hash + character;
			}
		}
		return hash;
	}

	/** Compares a validated escaped region to a decoded key without allocating a temporary String. */
	static boolean decodedEquals(@Nonnull String path, int start, int end, @Nonnull String key) {
		int position = start;
		int keyPosition = 0;
		while (position < end) {
			final long next = decodedCharacter(path, position);
			final int character = (int) next;
			position = (int) (next >>> 32);
			if (character > Character.MAX_VALUE) {
				if (keyPosition + 1 >= key.length() ||
					key.charAt(keyPosition++) != Character.highSurrogate(character) ||
					key.charAt(keyPosition++) != Character.lowSurrogate(character)) {
					return false;
				}
			} else if (keyPosition >= key.length() || key.charAt(keyPosition++) != character) {
				return false;
			}
		}
		return keyPosition == key.length();
	}

	/**
	 * Returns the next position in the high word and the decoded character or code point in the low word; the
	 * region must already be validated.
	 */
	private static long decodedCharacter(@Nonnull String path, int position) {
		final char character = path.charAt(position);
		if (character != '%') {
			return ((long) (position + 1) << 32) | character;
		}
		final int first = (hex(path.charAt(position + 1)) << 4) | hex(path.charAt(position + 2));
		final int count = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
		int codePoint = first & (count == 1 ? 0x7f : count == 2 ? 0x1f : count == 3 ? 0x0f : 0x07);
		position += 3;
		for (int i = 1; i < count; i++) {
			final int value = (hex(path.charAt(position + 1)) << 4) | hex(path.charAt(position + 2));
			codePoint = (codePoint << 6) | (value & 0x3f);
			position += 3;
		}
		return ((long) position << 32) | codePoint;
	}

	/**
	 * Decodes contiguous escape bytes exactly once as strict UTF-8; plus signs retain their literal meaning.
	 * Returns null for malformed triplets or UTF-8, which routers answer with 404. Armeria's request-target parser
	 * rejects malformed triplets but passes escaped non-ASCII bytes on without UTF-8 validation, so invalid UTF-8
	 * does reach routing, while a malformed triplet arrives only when a router is called directly.
	 */
	@Nullable
	static String decode(@Nonnull String path, int start, int end, boolean escaped) {
		if (!escaped) {
			return path.substring(start, end);
		}
		final StringBuilder decoded = new StringBuilder(end - start);
		final byte[] bytes = new byte[end - start];
		int position = start;
		while (position < end) {
			if (path.charAt(position) != '%') {
				decoded.append(path.charAt(position++));
				continue;
			}
			int count = 0;
			while (position < end && path.charAt(position) == '%') {
				if (position + 2 >= end) {
					return null;
				}
				final int high = hex(path.charAt(position + 1));
				final int low = hex(path.charAt(position + 2));
				if (high < 0 || low < 0) {
					return null;
				}
				bytes[count++] = (byte) ((high << 4) | low);
				position += 3;
			}
			try {
				decoded.append(StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes, 0, count)));
			} catch (CharacterCodingException exception) {
				return null;
			}
		}
		return decoded.toString();
	}
}
