/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.utils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;

import static java.util.Optional.ofNullable;

/**
 * Helper method for colored write to console.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ConsoleWriter {

	private static boolean isQuiet = false;

	/**
	 * Sets quiet mode. If true, no output is written to console.
	 *
	 * <b>Note:</b> this is not thread safe.
	 */
	public static void setQuiet(boolean quiet) {
		isQuiet = quiet;
	}

	/**
	 * Declaring ANSI_RESET so that we can reset the color
	 */
	public static final String ANSI_RESET = "\u001B[0m";

	@RequiredArgsConstructor
	public enum ConsoleColor {

		BLACK("\u001B[30m"),
		DARK_RED("\u001B[31m"),
		BRIGHT_RED("\u001B[91m"),
		DARK_GREEN("\u001B[32m"),
		BRIGHT_GREEN("\u001B[92m"),
		DARK_YELLOW("\u001B[33m"),
		BRIGHT_YELLOW("\u001B[93m"),
		DARK_BLUE("\u001B[34m"),
		BRIGHT_BLUE("\u001B[94m"),
		DARK_MAGENTA("\u001B[35m"),
		BRIGHT_MAGENTA("\u001B[95m"),
		DARK_CYAN("\u001B[36m"),
		LIGHT_CYAN("\u001B[96m"),
		WHITE("\u001B[37m"),
		LIGHT_GRAY("\u001B[37m"),
		DARK_GRAY("\u001B[90m");

		@Getter private final String controlChar;

	}

	@RequiredArgsConstructor
	public enum ConsoleDecoration {

		BOLD("\u001B[1m"),
		UNDERLINE("\u001B[4m");

		@Getter private final String controlChar;

	}

	/**
	 * Writes passed text in console.
	 */
	public static void write(@Nonnull String theString) {
		write(theString, (Object[]) null, null);
	}

	/**
	 * Writes passed text in console.
	 */
	public static void write(@Nonnull String theString, @Nullable Object[] arguments) {
		write(theString, arguments, null);
	}

	/**
	 * Writes passed text in console in designated color.
	 * @param theString
	 * @param color
	 */
	public static void write(@Nonnull String theString, @Nullable ConsoleColor color) {
		write(theString, null, color);
	}

	/**
	 * Writes passed text in console in designated color.
	 * @param theString
	 * @param color
	 */
	public static void write(@Nonnull String theString, @Nullable Object[] arguments, @Nullable ConsoleColor color) {
		write(theString, arguments, color, (ConsoleDecoration[]) null);
	}

	/**
	 * Writes passed text in console in designated color and format.
	 * @param theString
	 * @param color
	 */
	public static void write(@Nonnull String theString, @Nullable ConsoleColor color, @Nullable ConsoleDecoration... decoration) {
		write(theString, null, color, decoration);
	}

	/**
	 * Writes passed text in console in designated color and format.
	 * @param theString
	 * @param color
	 */
	public static void write(@Nonnull String theString, @Nullable Object[] arguments, @Nullable ConsoleColor color, @Nullable ConsoleDecoration... decoration) {
		if (Boolean.TRUE.equals(isQuiet)) {
			return;
		}

		ofNullable(color).ifPresent(it -> System.out.print(it.getControlChar()));
		ofNullable(decoration).stream().flatMap(Arrays::stream).forEach(it -> System.out.print(it.getControlChar()));
		System.out.printf(theString, arguments);
		System.out.print(ANSI_RESET);
	}
}
