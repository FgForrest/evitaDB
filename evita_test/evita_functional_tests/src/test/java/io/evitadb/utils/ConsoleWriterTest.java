/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link ConsoleWriter} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ConsoleWriter contract tests")
class ConsoleWriterTest {
	private ByteArrayOutputStream outputCapture;
	private PrintStream originalOut;

	@BeforeEach
	void setUp() {
		this.originalOut = System.out;
		this.outputCapture = new ByteArrayOutputStream();
		System.setOut(new PrintStream(this.outputCapture));
		ConsoleWriter.setQuiet(false);
	}

	@AfterEach
	void tearDown() {
		System.setOut(this.originalOut);
		ConsoleWriter.setQuiet(false);
	}

	@Nested
	@DisplayName("Quiet mode tests")
	class QuietModeTests {

		@Test
		@DisplayName("Should not write when quiet")
		void shouldNotWriteWhenQuiet() {
			ConsoleWriter.setQuiet(true);
			ConsoleWriter.write("test");
			final String output = outputCapture.toString();
			assertTrue(output.isEmpty(), "Output should be empty when quiet mode is enabled");
		}

		@Test
		@DisplayName("Should write when not quiet")
		void shouldWriteWhenNotQuiet() {
			ConsoleWriter.setQuiet(false);
			ConsoleWriter.write("test");
			final String output = outputCapture.toString();
			assertTrue(output.contains("test"), "Output should contain the message when quiet mode is disabled");
		}

		@Test
		@DisplayName("Should not write line when quiet")
		void shouldNotWriteLineWhenQuiet() {
			ConsoleWriter.setQuiet(true);
			ConsoleWriter.writeLine("test");
			final String output = outputCapture.toString();
			assertTrue(output.isEmpty(), "Output should be empty when quiet mode is enabled");
		}
	}

	@Nested
	@DisplayName("Write tests")
	class WriteTests {

		@Test
		@DisplayName("Should write simple string")
		void shouldWriteSimpleString() {
			ConsoleWriter.write("Hello World");
			final String output = outputCapture.toString();
			assertTrue(output.contains("Hello World"));
		}

		@Test
		@DisplayName("Should write with format args")
		void shouldWriteWithFormatArgs() {
			ConsoleWriter.write("Value: %d", new Object[]{42});
			final String output = outputCapture.toString();
			assertTrue(output.contains("Value: 42"));
		}

		@Test
		@DisplayName("Should write line with newline")
		void shouldWriteLineWithNewline() {
			ConsoleWriter.writeLine("Line1");
			final String output = outputCapture.toString();
			assertTrue(output.contains("Line1"));
			assertTrue(output.contains(System.lineSeparator()));
		}

		@Test
		@DisplayName("Should write with color")
		void shouldWriteWithColor() {
			ConsoleWriter.write("Colored", ConsoleColor.BRIGHT_RED);
			final String output = outputCapture.toString();
			assertTrue(output.contains("Colored"));
			assertTrue(output.contains(ConsoleColor.BRIGHT_RED.getControlChar()));
			assertTrue(output.contains(ConsoleWriter.ANSI_RESET));
		}

		@Test
		@DisplayName("Should write with decoration")
		void shouldWriteWithDecoration() {
			ConsoleWriter.write("Bold", ConsoleColor.WHITE, ConsoleDecoration.BOLD);
			final String output = outputCapture.toString();
			assertTrue(output.contains("Bold"));
			assertTrue(output.contains(ConsoleDecoration.BOLD.getControlChar()));
			assertTrue(output.contains(ConsoleWriter.ANSI_RESET));
		}

		@Test
		@DisplayName("Should write with multiple decorations")
		void shouldWriteWithMultipleDecorations() {
			ConsoleWriter.write("Styled", ConsoleColor.BRIGHT_GREEN, ConsoleDecoration.BOLD, ConsoleDecoration.UNDERLINE);
			final String output = outputCapture.toString();
			assertTrue(output.contains("Styled"));
			assertTrue(output.contains(ConsoleDecoration.BOLD.getControlChar()));
			assertTrue(output.contains(ConsoleDecoration.UNDERLINE.getControlChar()));
		}

		@Test
		@DisplayName("Should write with null color")
		void shouldWriteWithNullColor() {
			ConsoleWriter.write("No Color", (ConsoleColor) null);
			final String output = outputCapture.toString();
			assertTrue(output.contains("No Color"));
			assertFalse(output.contains("\u001B[3"), "Should not contain ANSI color codes");
		}

		@Test
		@DisplayName("Should write with null arguments")
		void shouldWriteWithNullArguments() {
			ConsoleWriter.write("Simple", (Object[]) null, null);
			final String output = outputCapture.toString();
			assertTrue(output.contains("Simple"));
		}

		@Test
		@DisplayName("Should write line with all parameters")
		void shouldWriteLineWithAllParameters() {
			ConsoleWriter.writeLine("Full", new Object[]{}, ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
			final String output = outputCapture.toString();
			assertTrue(output.contains("Full"));
			assertTrue(output.contains(System.lineSeparator()));
			assertTrue(output.contains(ConsoleColor.DARK_BLUE.getControlChar()));
			assertTrue(output.contains(ConsoleDecoration.UNDERLINE.getControlChar()));
		}
	}

	@Nested
	@DisplayName("Console color tests")
	class ConsoleColorTests {

		@Test
		@DisplayName("Should have control chars for all colors")
		void shouldHaveControlCharsForAllColors() {
			for (final ConsoleColor color : ConsoleColor.values()) {
				final String controlChar = color.getControlChar();
				assertFalse(controlChar.isEmpty(), "Color " + color + " should have a control char");
				assertTrue(controlChar.startsWith("\u001B["), "Control char should start with ANSI escape sequence");
			}
		}
	}

	@Nested
	@DisplayName("Console decoration tests")
	class ConsoleDecorationTests {

		@Test
		@DisplayName("Should have control chars for all decorations")
		void shouldHaveControlCharsForAllDecorations() {
			for (final ConsoleDecoration decoration : ConsoleDecoration.values()) {
				final String controlChar = decoration.getControlChar();
				assertFalse(controlChar.isEmpty(), "Decoration " + decoration + " should have a control char");
				assertTrue(controlChar.startsWith("\u001B["), "Control char should start with ANSI escape sequence");
			}
		}

		@Test
		@DisplayName("Should have correct bold control char")
		void shouldHaveCorrectBoldControlChar() {
			assertEquals("\u001B[1m", ConsoleDecoration.BOLD.getControlChar());
		}

		@Test
		@DisplayName("Should have correct underline control char")
		void shouldHaveCorrectUnderlineControlChar() {
			assertEquals("\u001B[4m", ConsoleDecoration.UNDERLINE.getControlChar());
		}
	}
}
