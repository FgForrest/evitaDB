/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.reflect.Array.get;
import static java.lang.reflect.Array.getLength;

/**
 * String utils contains shared utility methods for working with Strings.
 * We know some of these functions are available in Apache Commons, but we try to keep our transitive dependencies as low as
 * possible, so we rather went through duplication of the code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class StringUtils {

	/**
	 * The number of bytes in a kilobyte.
	 */
	public static final int ONE_KB = 1024;
	/**
	 * The number of bytes in a megabyte.
	 */
	public static final int ONE_MB = ONE_KB * ONE_KB;
	/**
	 * The number of bytes in a gigabyte.
	 */
	public static final int ONE_GB = ONE_KB * ONE_MB;
	/**
	 * Universal word splitter inspired by <a href="https://regex101.com/library/zT4rM9">this</a>.
	 */
	private static final Pattern STRING_WITH_CASE_WORD_SPLITTING_PATTERN = Pattern.compile("([^\\s\\-_A-Z]+)|([A-Z]+[^\\s\\-_A-Z]*)");
	/**
	 * Finds unsupported characters in concrete cases (not base case).
	 * Characters are based on {@link ClassifierUtils#SUPPORTED_FORMAT_PATTERN} regex.
	 */
	private static final Pattern UNSUPPORTED_CHARACTERS_FOR_WORD_SPLITTING_PATTERN = Pattern.compile("[.:+\\-@/\\\\|`~]");

	/**
	 * Displays bytes in human-readable form (i.e. using shortening for kB, MB, GB and so on).
	 */
	@Nonnull
	public static String formatByteSize(int sizeInBytes) {
		if (sizeInBytes / ONE_GB > 0) {
			return String.format("%.2f GB", (double) sizeInBytes / ONE_GB);
		} else if (sizeInBytes / ONE_MB > 0) {
			return String.format("%.2f MB", (double) sizeInBytes / ONE_MB);
		} else if (sizeInBytes / ONE_KB > 0) {
			return sizeInBytes / ONE_KB + " KB";
		} else {
			return sizeInBytes + " B";
		}
	}

	/**
	 * Displays bytes in human-readable form (i.e. using shortening for kB, MB, GB and so on).
	 */
	@Nonnull
	public static String formatByteSize(long sizeInBytes) {
		if (sizeInBytes / ONE_GB > 0) {
			return String.format("%.2f GB", (double) sizeInBytes / ONE_GB);
		} else if (sizeInBytes / ONE_MB > 0) {
			return String.format("%.2f MB", (double) sizeInBytes / ONE_MB);
		} else if (sizeInBytes / ONE_KB > 0) {
			return sizeInBytes / ONE_KB + " KB";
		} else {
			return sizeInBytes + " B";
		}
	}

	/**
	 * Displays high number (count) in human-readable form (i.e. using shortening for thousands, millions and so on).
	 */
	@Nonnull
	public static String formatCount(int count) {
		if (count / 1_000_000_000 > 0) {
			return String.format("%.2f bil.", (double) count / 1_000_000_000);
		} else if (count / 100_000 > 0) {
			return String.format("%.2f mil.", (double) count / 1_000_000);
		} else if (count / 1_000 > 0) {
			return String.format("%.2f thousands", (double) count / 1_000);
		} else {
			return String.valueOf(count);
		}
	}

	/**
	 * Displays high number (count) in human-readable form (i.e. using shortening for thousands, millions and so on).
	 */
	@Nonnull
	public static String formatCount(long count) {
		if (count / 1_000_000_000 > 0) {
			return String.format("%.2f bil.", (double) count / 1_000_000_000);
		} else if (count / 100_000 > 0) {
			return String.format("%.2f mil.", (double) count / 1_000_000);
		} else if (count / 1_000 > 0) {
			return String.format("%.2f thousands", (double) count / 1_000);
		} else {
			return String.valueOf(count);
		}
	}

	/**
	 * Formats value in nanoseconds (used for measuring elapsed time) to human readable format.
	 * Nanoseconds are omitted when at least second is printed.
	 */
	@Nonnull
	public static String formatNano(long nanoSeconds) {
		return formatNano(nanoSeconds, true);
	}

	/**
	 * Formats value in nanoseconds (used for measuring elapsed time) to human readable format.
	 * Nanoseconds are not omitted and printed every time - even if nano spans days.
	 */
	@Nonnull
	public static String formatPreciseNano(long nanoSeconds) {
		return formatNano(nanoSeconds, false);
	}

	/**
	 * Lower cases first character of the string.
	 */
	@Nullable
	public static String uncapitalize(@Nullable String string) {
		if (string == null || string.isEmpty()) {
			return string;
		}

		char firstCharacter = Character.toLowerCase(string.charAt(0));
		if (firstCharacter == string.charAt(0)) {
			return string;
		}

		char[] chars = string.toCharArray();
		chars[0] = firstCharacter;
		return new String(chars);
	}

	/**
	 * Upper cases first character of the string.
	 */
	@Nullable
	public static String capitalize(@Nullable String string) {
		if (string == null || string.isEmpty()) {
			return string;
		}

		char firstCharacter = Character.toUpperCase(string.charAt(0));
		if (firstCharacter == string.charAt(0)) {
			return string;
		}

		char[] chars = string.toCharArray();
		chars[0] = firstCharacter;
		return new String(chars);
	}

	/**
	 * Removes national diacritic characters and replaces them with ASCII characters.
	 * equivalents.
	 */
	@Nonnull
	public static String removeDiacritics(@Nonnull String original) {
		final String normalizedResult = Normalizer.normalize(original, Form.NFKD);
		return normalizedResult.replaceAll("[^\\p{ASCII}]", "");
	}

	/**
	 * Removes national diacritic characters and replaces them with ASCII equivalents. Finally removes all non alphanumeric
	 * characters (respecting exceptions) and replaces them with
	 *
	 * @param charactersToKeep in regular expression form
	 */
	@Nonnull
	public static String removeDiacriticsAndAllNonStandardCharactersExcept(@Nonnull String original, char sanitizeCharacter, @Nonnull String charactersToKeep) {
		final String asciiName = removeDiacritics(original);
		final String sanitizeString = String.valueOf(sanitizeCharacter);
		final String quotedSanitizeString = Pattern.quote(sanitizeString);
		return asciiName.replaceAll("[^A-Za-z0-9" + charactersToKeep + "]", sanitizeString)
			.replaceAll(quotedSanitizeString + "+", sanitizeString)
			.replaceAll("\\A" + quotedSanitizeString + "+", "")
			.replaceAll(quotedSanitizeString + "+\\z", "");
	}

	/**
	 * Computes and returns requests per second rounded to two decimal places.
	 */
	public static String formatRequestsPerSec(int requestsProcessed, long nanoSeconds) {
		if (requestsProcessed == 0) {
			return "N/A";
		} else {
			final double reqsSec = requestsProcessed / ((double) nanoSeconds / (double) 1_000_000_000);
			return new BigDecimal(String.valueOf(reqsSec)).setScale(2, RoundingMode.HALF_UP) + " reqs/s";
		}
	}

	/**
	 * Prints unknown object as human readable string.
	 */
	public static String unknownToString(@Nullable Object value) {
		if (value instanceof Object[]) {
			return Arrays.toString((Object[]) value);
		} else if (value != null) {
			return value.toString();
		} else {
			return "<NULL>";
		}
	}

	/**
	 * Switches case of {@code s} to {@code c} case. The original {@code s} must have some other case, so that it can be
	 * split into words.
	 *
	 * Note: if there are any numbers in passed string, a reverse conversion may not result in original string due to
	 * an ambiguity of not knowing when to split numbers from letter or between themselves.
	 *
	 * @param s                      string to convert
	 * @param targetNamingConvention target naming convention of passed string
	 * @return string in target case
	 */
	@Nonnull
	public static String toSpecificCase(@Nonnull String s, @Nonnull NamingConvention targetNamingConvention) {
		return switch (targetNamingConvention) {
			case CAMEL_CASE -> StringUtils.toCamelCase(s);
			case PASCAL_CASE -> StringUtils.toPascalCase(s);
			case SNAKE_CASE -> StringUtils.toSnakeCase(s);
			case UPPER_SNAKE_CASE -> StringUtils.toUpperSnakeCase(s);
			case KEBAB_CASE -> StringUtils.toKebabCase(s);
		};
	}

	/**
	 * Switches case of {@code s} to camelCase. The original {@code s} must have some other case, so that it can be
	 * split into words.
	 *
	 * Note: if there are any numbers in passed string, a reverse conversion may not result in original string due to
	 * an ambiguity of not knowing when to split numbers from letter or between themselves.
	 *
	 * @param s string to convert
	 * @return string in camelCase
	 */
	@Nonnull
	public static String toCamelCase(@Nonnull String s) {
		final List<String> words = splitStringWithCaseIntoWords(s);
		return Objects.requireNonNull(
			uncapitalize(
				words.stream()
					.map(word -> capitalize(word.toLowerCase()))
					.collect(Collectors.joining())
			)
		);
	}

	/**
	 * Switches case of {@code s} to PascalCase. The original {@code s} must have some other case, so that it can be
	 * split into words.
	 *
	 * Note: if there are any numbers in passed string, a reverse conversion may not result in original string due to
	 * an ambiguity of not knowing when to split numbers from letter or between themselves.
	 *
	 * @param s string to convert
	 * @return string in PascalCase
	 */
	@Nonnull
	public static String toPascalCase(@Nonnull String s) {
		final List<String> words = splitStringWithCaseIntoWords(s);
		return words.stream()
			.map(word -> capitalize(word.toLowerCase()))
			.collect(Collectors.joining());
	}

	/**
	 * Switches case of {@code s} to snake_case. The original {@code s} must have some other case, so that it can be
	 * split into words.
	 *
	 * @param s string to convert
	 * @return string in snake_case
	 */
	@Nonnull
	public static String toSnakeCase(@Nonnull String s) {
		final List<String> words = splitStringWithCaseIntoWords(s);
		return words.stream()
			.map(String::toLowerCase)
			.collect(Collectors.joining("_"));
	}

	/**
	 * Switches case of {@code s} to UPPER_SNAKE_CASE. The original {@code s} must have some other case, so that it can be
	 * split into words.
	 *
	 * @param s string to convert
	 * @return string in UPPER_SNAKE_CASE
	 */
	@Nonnull
	public static String toUpperSnakeCase(@Nonnull String s) {
		final List<String> words = splitStringWithCaseIntoWords(s);
		return words.stream()
			.map(String::toUpperCase)
			.collect(Collectors.joining("_"));
	}

	/**
	 * Switches case of {@code s} to kebab-case. The original {@code s} must have some other case, so that it can be
	 * split into words.
	 *
	 * @param s string to convert
	 * @return string in kebab-case
	 */
	@Nonnull
	public static String toKebabCase(@Nonnull String s) {
		final List<String> words = splitStringWithCaseIntoWords(s);
		return words.stream()
			.map(String::toLowerCase)
			.collect(Collectors.joining("-"));
	}

	/**
	 * Splits string which is in certain case (camelCase, snake_case, ...) to individual words for transforming the string
	 * to other cases.
	 */
	@Nonnull
	public static List<String> splitStringWithCaseIntoWords(@Nullable String s) {
		if (s == null || s.isBlank()) {
			return List.of();
		}

		String newString = s;

		// remove unsupported characters in concrete cases (not base case)
		// characters are based on ClassifierUtils#SUPPORTED_FORMAT_PATTERN regex
		newString = UNSUPPORTED_CHARACTERS_FOR_WORD_SPLITTING_PATTERN.matcher(newString).replaceAll(" ");

		return STRING_WITH_CASE_WORD_SPLITTING_PATTERN.matcher(newString)
			.results()
			.map(MatchResult::group)
			.toList();
	}

	/**
	 * Returns MD5 hash of the passed string.
	 */
	@Nonnull
	public static String hashChars(@Nonnull String string) {
		try {
			final MessageDigest md5 = MessageDigest.getInstance("MD5");
			md5.update(string.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(md5.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns `theText` padded with `padCharacter` to the requested size.
	 *
	 * @param theText       to repeat
	 * @param padCharacter  padding character
	 * @param requestedSize requested total size
	 * @return padded string
	 */
	@Nonnull
	public static String rightPad(@Nonnull String theText, @Nonnull String padCharacter, int requestedSize) {
		return theText + padCharacter.repeat(Math.max(0, requestedSize - theText.length()));
	}

	/**
	 * Returns `theText` padded with `padCharacter` to the requested size on the left.
	 *
	 * @param theText       to repeat
	 * @param padCharacter  padding character
	 * @param requestedSize requested total size
	 * @return padded string
	 */
	@Nonnull
	public static String leftPad(@Nonnull String theText, @Nonnull String padCharacter, int requestedSize) {
		return padCharacter.repeat(Math.max(0, requestedSize - theText.length())) + theText;
	}

	/**
	 * Method taken from Java - String. See copyright notice for JDK.
	 *
	 * Returns a string whose value is the passed string, with escape sequences
	 * translated as if in a string literal.
	 * Base code borrowed from {@link String#translateEscapes()} and extended to support unicode escape sequences.
	 *
	 * @return String with escape sequences translated.
	 * @throws IllegalArgumentException when an escape sequence is malformed.
	 */
	@Nonnull
	public static String translateEscapes(@Nonnull String s) {
		if (s.isEmpty()) {
			return "";
		}
		char[] chars = s.toCharArray();
		int length = chars.length;
		int from = 0;
		int to = 0;
		while (from < length) {
			char[] ch = {chars[from++]};
			if (ch[0] == '\\') {
				ch[0] = from < length ? chars[from++] : '\0';
				switch (ch[0]) {
					case 'b':
						ch[0] = '\b';
						break;
					case 'f':
						ch[0] = '\f';
						break;
					case 'n':
						ch[0] = '\n';
						break;
					case 'r':
						ch[0] = '\r';
						break;
					case 's':
						ch[0] = ' ';
						break;
					case 't':
						ch[0] = '\t';
						break;
					case 'u':
						final int unicodeCodepoint = Integer.parseInt(new String(chars, from, 4), 16);
						ch = Character.toChars(unicodeCodepoint);
						from += 4;
						break;
					case '\'':
					case '\"':
					case '\\':
						// as is
						break;
					case '0':
					case '1':
					case '2':
					case '3':
					case '4':
					case '5':
					case '6':
					case '7':
						int limit = Integer.min(from + (ch[0] <= '3' ? 2 : 1), length);
						int code = ch[0] - '0';
						while (from < limit) {
							ch[0] = chars[from];
							if (ch[0] < '0' || '7' < ch[0]) {
								break;
							}
							from++;
							code = (code << 3) | (ch[0] - '0');
						}
						ch[0] = (char) code;
						break;
					case '\n':
						continue;
					case '\r':
						if (from < length && chars[from] == '\n') {
							from++;
						}
						continue;
					default: {
						String msg = String.format(
							"Invalid escape sequence: \\%c \\\\u%04X",
							ch[0], (int) ch[0]);
						throw new IllegalArgumentException(msg);
					}
				}
			}

			for (char c : ch) {
				chars[to++] = c;
			}
		}

		return new String(chars, 0, to);
	}

	/**
	 * Method taken from Apache Commons - StringUtils. See copyright notice for Apache Commons.
	 *
	 * <p>
	 * Replaces all occurrences of Strings within another String.
	 * </p>
	 *
	 * <p>
	 * A {@code null} reference passed to this method is a no-op, or if
	 * any "search string" or "string to replace" is null, that replace will be
	 * ignored. This will not repeat. For repeating replaces, call the
	 * overloaded method.
	 * </p>
	 *
	 * <pre>
	 *  StringUtils.replaceEach(null, *, *)        = null
	 *  StringUtils.replaceEach("", *, *)          = ""
	 *  StringUtils.replaceEach("aba", null, null) = "aba"
	 *  StringUtils.replaceEach("aba", new String[0], null) = "aba"
	 *  StringUtils.replaceEach("aba", null, new String[0]) = "aba"
	 *  StringUtils.replaceEach("aba", new String[]{"a"}, null)  = "aba"
	 *  StringUtils.replaceEach("aba", new String[]{"a"}, new String[]{""})  = "b"
	 *  StringUtils.replaceEach("aba", new String[]{null}, new String[]{"a"})  = "aba"
	 *  StringUtils.replaceEach("abcde", new String[]{"ab", "d"}, new String[]{"w", "t"})  = "wcte"
	 *  (example of how it does not repeat)
	 *  StringUtils.replaceEach("abcde", new String[]{"ab", "d"}, new String[]{"d", "t"})  = "dcte"
	 * </pre>
	 *
	 * @param text            text to search and replace in, no-op if null
	 * @param searchList      the Strings to search for, no-op if null
	 * @param replacementList the Strings to replace them with, no-op if null
	 * @return the text with any replacements processed, {@code null} if
	 * null String input
	 * @throws IllegalArgumentException if the lengths of the arrays are not the same (null is ok,
	 *                                  and/or size 0)
	 * @see org.apache.commons.lang3.StringUtils#replaceEach(String, String[], String[])
	 * @since 2.4
	 */
	public static String replaceEach(final String text, final String[] searchList, final String[] replacementList) {
		return replaceEach(text, searchList, replacementList, false, 0);
	}

	/**
	 * Formats duration to human readable format.
	 *
	 * @param duration duration to be formatted
	 * @return formatted duration
	 */
	@Nonnull
	public static String formatDuration(@Nonnull Duration duration) {
		long days = duration.toDaysPart();
		long hours = duration.toHoursPart();
		long minutes = duration.toMinutesPart();
		long seconds = duration.toSecondsPart();
		long milliSeconds = duration.toMillis();

		StringBuilder sb = new StringBuilder(32);

		if (days > 0) {
			sb.append(days).append("d ");
		}
		if (hours > 0 || days > 0) {
			sb.append(hours).append("h ");
		}
		if (minutes > 0 || hours > 0 || days > 0) {
			sb.append(minutes).append("m ");
		}
		if (days > 0 || hours > 0 || minutes > 0 || seconds > 0) {
			sb.append(seconds).append("s");
		} else {
			sb.append(milliSeconds).append("ms");
		}

		return sb.toString().trim();
	}

	/**
	 * Renders value as String taking into account NULL values and arrays.
	 *
	 * @param value value to render
	 * @return rendered value
	 */
	@Nonnull
	public static String toString(@Nullable Object value) {
		if (value == null) {
			return "NULL";
		} else if (value.getClass().isArray()) {
			final StringBuilder sb = new StringBuilder(256);
			sb.append('[');
			for (int i = 0; i < getLength(value); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(toString(get(value, i)));

			}
			sb.append(']');
			return sb.toString();
		} else {
			return value.toString();
		}
	}

	/**
	 * Normalizes the line endings in the given string by replacing all occurrences
	 * of Windows-style line endings ("\r\n") with Unix-style line endings ("\n").
	 *
	 * @param inputString the input string containing line endings to normalize; must not be null
	 * @return a new string with all line endings normalized to "\n"
	 */
	@Nonnull
	public static String normalizeLineEndings(@Nonnull String inputString) {
		return inputString.replace("\r\n", "\n");
	}

	/**
	 * Method taken from Apache Commons - StringUtils. See copyright notice for Apache Commons.
	 *
	 * <p>
	 * Replace all occurrences of Strings within another String.
	 * This is a private recursive helper method for {@link #replaceEach(String, String[], String[])} and
	 * {@link #replaceEach(String, String[], String[])}
	 * </p>
	 *
	 * <p>
	 * A {@code null} reference passed to this method is a no-op, or if
	 * any "search string" or "string to replace" is null, that replace will be
	 * ignored.
	 * </p>
	 *
	 * <pre>
	 *  StringUtils.replaceEach(null, *, *, *, *) = null
	 *  StringUtils.replaceEach("", *, *, *, *) = ""
	 *  StringUtils.replaceEach("aba", null, null, *, *) = "aba"
	 *  StringUtils.replaceEach("aba", new String[0], null, *, *) = "aba"
	 *  StringUtils.replaceEach("aba", null, new String[0], *, *) = "aba"
	 *  StringUtils.replaceEach("aba", new String[]{"a"}, null, *, *) = "aba"
	 *  StringUtils.replaceEach("aba", new String[]{"a"}, new String[]{""}, *, >=0) = "b"
	 *  StringUtils.replaceEach("aba", new String[]{null}, new String[]{"a"}, *, >=0) = "aba"
	 *  StringUtils.replaceEach("abcde", new String[]{"ab", "d"}, new String[]{"w", "t"}, *, >=0) = "wcte"
	 *  (example of how it repeats)
	 *  StringUtils.replaceEach("abcde", new String[]{"ab", "d"}, new String[]{"d", "t"}, false, >=0) = "dcte"
	 *  StringUtils.replaceEach("abcde", new String[]{"ab", "d"}, new String[]{"d", "t"}, true, >=2) = "tcte"
	 *  StringUtils.replaceEach("abcde", new String[]{"ab", "d"}, new String[]{"d", "ab"}, *, *) = IllegalStateException
	 * </pre>
	 *
	 * @param text            text to search and replace in, no-op if null
	 * @param searchList      the Strings to search for, no-op if null
	 * @param replacementList the Strings to replace them with, no-op if null
	 * @param repeat          if true, then replace repeatedly
	 *                        until there are no more possible replacements or timeToLive < 0
	 * @param timeToLive      if less than 0 then there is a circular reference and endless
	 *                        loop
	 * @return the text with any replacements processed, {@code null} if
	 * null String input
	 * @throws IllegalStateException    if the search is repeating and there is an endless loop due
	 *                                  to outputs of one being inputs to another
	 * @throws IllegalArgumentException if the lengths of the arrays are not the same (null is ok,
	 *                                  and/or size 0)
	 * @see org.apache.commons.lang3.StringUtils#replaceEach(String, String[], String[], boolean, int)
	 * @since 2.4
	 */
	@Nullable
	private static String replaceEach(
		@Nullable final String text,
		@Nullable final String[] searchList,
		@Nullable final String[] replacementList,
		final boolean repeat,
		final int timeToLive
	) {

		// mchyzer Performance note: This creates very few new objects (one major goal)
		// let me know if there are performance requests, we can create a harness to measure

		if (text == null || text.isEmpty() || searchList == null ||
			searchList.length == 0 || replacementList == null || replacementList.length == 0) {
			return text;
		}

		// if recursing, this shouldn't be less than 0
		if (timeToLive < 0) {
			throw new IllegalStateException("Aborting to protect against StackOverflowError - " +
				"output of one loop is the input of another");
		}

		final int searchLength = searchList.length;
		final int replacementLength = replacementList.length;

		// make sure lengths are ok, these need to be equal
		if (searchLength != replacementLength) {
			throw new IllegalArgumentException("Search and Replace array lengths don't match: "
				+ searchLength
				+ " vs "
				+ replacementLength);
		}

		// keep track of which still have matches
		final boolean[] noMoreMatchesForReplIndex = new boolean[searchLength];

		// index on index that the match was found
		int textIndex = -1;
		int replaceIndex = -1;
		int tempIndex = -1;

		// index of replace array that will replace the search string found
		// NOTE: logic duplicated below START
		for (int i = 0; i < searchLength; i++) {
			if (noMoreMatchesForReplIndex[i] || searchList[i] == null ||
				searchList[i].isEmpty() || replacementList[i] == null) {
				continue;
			}
			tempIndex = text.indexOf(searchList[i]);

			// see if we need to keep searching for this
			if (tempIndex == -1) {
				noMoreMatchesForReplIndex[i] = true;
			} else {
				if (textIndex == -1 || tempIndex < textIndex) {
					textIndex = tempIndex;
					replaceIndex = i;
				}
			}
		}
		// NOTE: logic mostly below END

		// no search strings found, we are done
		if (textIndex == -1) {
			return text;
		}

		int start = 0;

		// get a good guess on the size of the result buffer so it doesn't have to double if it goes over a bit
		int increase = 0;

		// count the replacement text elements that are larger than their corresponding text being replaced
		for (int i = 0; i < searchList.length; i++) {
			if (searchList[i] == null || replacementList[i] == null) {
				continue;
			}
			final int greater = replacementList[i].length() - searchList[i].length();
			if (greater > 0) {
				increase += 3 * greater; // assume 3 matches
			}
		}
		// have upper-bound at 20% increase, then let Java take over
		increase = Math.min(increase, text.length() / 5);

		final StringBuilder buf = new StringBuilder(text.length() + increase);

		while (textIndex != -1) {

			for (int i = start; i < textIndex; i++) {
				buf.append(text.charAt(i));
			}
			buf.append(replacementList[replaceIndex]);

			start = textIndex + searchList[replaceIndex].length();

			textIndex = -1;
			replaceIndex = -1;
			tempIndex = -1;
			// find the next earliest match
			// NOTE: logic mostly duplicated above START
			for (int i = 0; i < searchLength; i++) {
				if (noMoreMatchesForReplIndex[i] || searchList[i] == null ||
					searchList[i].isEmpty() || replacementList[i] == null) {
					continue;
				}
				tempIndex = text.indexOf(searchList[i], start);

				// see if we need to keep searching for this
				if (tempIndex == -1) {
					noMoreMatchesForReplIndex[i] = true;
				} else {
					if (textIndex == -1 || tempIndex < textIndex) {
						textIndex = tempIndex;
						replaceIndex = i;
					}
				}
			}
			// NOTE: logic duplicated above END

		}
		final int textLength = text.length();
		for (int i = start; i < textLength; i++) {
			buf.append(text.charAt(i));
		}
		final String result = buf.toString();
		if (!repeat) {
			return result;
		}

		return replaceEach(result, searchList, replacementList, repeat, timeToLive - 1);
	}

	/**
	 * Converts an array of {@link Serializable} objects into a single string by
	 * concatenating their string representations, separated by commas.
	 *
	 * @param thisRepAV an array of {@link Serializable} objects to be converted into a string
	 * @return a string representation of the input array, where each element is separated by a comma
	 */
	@Nonnull
	public static String serializableArrayToString(@Nonnull Serializable[] thisRepAV) {
		// Avoid stream/collector allocations; use a single StringBuilder and a tight loop
		final int len = thisRepAV.length;
		if (len == 0) {
			return "";
		}
		// Heuristic initial capacity: average 8 chars per element plus separator space/comma
		final StringBuilder sb = new StringBuilder(len * 10);
		for (int i = 0; i < len; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			final Serializable item = thisRepAV[i];
			sb.append(item == null ? "NULL" : item.toString());
		}
		return sb.toString();
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Formats value in nanoseconds (used for measuring elapsed time) to human-readable format.
	 */
	@Nonnull
	private static String formatNano(long nanoSeconds, boolean omitNanoIfPossible) {
		final StringBuilder sb = new StringBuilder(128);
		long seconds = nanoSeconds / 1000000000;
		long days = seconds / (3600 * 24);
		appendIfNonZero(sb, days, "d");
		seconds -= (days * 3600 * 24);
		long hours = seconds / 3600;
		appendIfNonZero(sb, hours, "h");
		seconds -= (hours * 3600);
		long minutes = seconds / 60;
		appendIfNonZero(sb, minutes, "m");

		seconds -= (minutes * 60);
		final boolean emptyWithoutSeconds = sb.isEmpty() && seconds == 0;
		if (!omitNanoIfPossible || emptyWithoutSeconds) {
			if (emptyWithoutSeconds) {
				final long millis = nanoSeconds / 1000000;
				long nanos = nanoSeconds - (millis * 1000000);
				final String suffix = getIfNonZero(nanos, "." + "0".repeat(6 - String.valueOf(nanos).length()), "ms");
				append(sb, millis, "", suffix);
			} else {
				long nanos = nanoSeconds % 1000000000;
				final String suffix = getIfNonZero(nanos, "." + "0".repeat(9 - String.valueOf(nanos).length()), "s");
				append(sb, seconds, "", suffix);
			}
		} else {
			appendIfNonZero(sb, seconds, "s");
		}

		return sb.toString();
	}

	/**
	 * Appends `prefix`, `value` and suffix to the `sb` StringBuilder when `value` is greater than zero.
	 */
	private static void appendIfNonZero(@Nonnull StringBuilder sb, long value, @Nonnull String suffix) {
		if (value > 0) {
			append(sb, value, "", suffix);
		}
	}

	/**
	 * Envelopes `value` with `prefix` and suffix when `value` is greater than zero.
	 */
	@Nonnull
	private static String getIfNonZero(long value, @Nonnull String prefix, @Nonnull String suffix) {
		final StringBuilder sb = new StringBuilder(prefix.length() + 1 + suffix.length());
		if (value > 0) {
			append(sb, value, prefix, suffix);
		}
		return sb.toString();
	}

	/**
	 * Appends `prefix`, `value` and suffix to the `sb` StringBuilder.
	 */
	private static void append(@Nonnull StringBuilder sb, long value, @Nonnull String prefix, @Nonnull String suffix) {
		if (!sb.isEmpty()) {
			sb.append(" ");
		}
		sb.append(prefix).append(value).append(suffix);
	}
}
