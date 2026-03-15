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

package io.evitadb.documentation.javadoc;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;
import io.evitadb.documentation.javadoc.JavaDocSummarizer.MethodInfo;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JavaDocSummarizer} verifying hash computation, JavaDoc formatting, method signature building,
 * annotation extraction, and source file rewriting logic.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("JavaDocSummarizer")
class JavaDocSummarizerTest implements EvitaTestSupport {

	private static final String TEST_CONSTRAINT_CLASS = "io.evitadb.api.query.filter.Foo";

	@Nested
	@DisplayName("MD5 hash computation")
	class MD5HashComputationTest {

		@Test
		@DisplayName("should return consistent hash for identical inputs")
		void shouldReturnConsistentHashForSameInputs() {
			final String hash1 = JavaDocSummarizer.computeHash("Some JavaDoc content", "void doSomething()");
			final String hash2 = JavaDocSummarizer.computeHash("Some JavaDoc content", "void doSomething()");

			assertEquals(hash1, hash2);
		}

		@Test
		@DisplayName("should return 32-character lowercase hexadecimal string")
		void shouldReturn32CharHexString() {
			final String hash = JavaDocSummarizer.computeHash("doc", "sig");

			assertEquals(32, hash.length());
			assertTrue(hash.matches("[0-9a-f]{32}"), "Hash should be lowercase hex: " + hash);
		}

		@Test
		@DisplayName("should return different hash when JavaDoc changes")
		void shouldReturnDifferentHashWhenJavaDocChanges() {
			final String hash1 = JavaDocSummarizer.computeHash("Original doc", "void method()");
			final String hash2 = JavaDocSummarizer.computeHash("Modified doc", "void method()");

			assertNotEquals(hash1, hash2);
		}

		@Test
		@DisplayName("should return different hash when method signature changes")
		void shouldReturnDifferentHashWhenSignatureChanges() {
			final String hash1 = JavaDocSummarizer.computeHash("Same doc", "void method()");
			final String hash2 = JavaDocSummarizer.computeHash("Same doc", "void method(int x)");

			assertNotEquals(hash1, hash2);
		}

		@Test
		@DisplayName("should handle empty strings as valid inputs")
		void shouldHandleEmptyStrings() {
			final String hash = JavaDocSummarizer.computeHash("", "");

			assertNotNull(hash);
			assertEquals(32, hash.length());
			assertTrue(hash.matches("[0-9a-f]{32}"));
		}

		@Test
		@DisplayName("should handle Unicode content in inputs")
		void shouldHandleUnicodeContent() {
			final String hash = JavaDocSummarizer.computeHash(
				"Příliš žluťoučký kůň", "void método(String parámetro)"
			);

			assertNotNull(hash);
			assertEquals(32, hash.length());
			assertTrue(hash.matches("[0-9a-f]{32}"));
		}
	}

	@Nested
	@DisplayName("JavaDoc formatting")
	class JavaDocFormattingTest {

		@Test
		@DisplayName("should wrap single line in JavaDoc block with @see tag")
		void shouldWrapSingleLineInJavaDocBlock() {
			final List<String> result = JavaDocSummarizer.formatJavaDoc("Simple summary.", TEST_CONSTRAINT_CLASS);

			assertEquals(5, result.size());
			assertEquals("\t/**", result.get(0));
			assertEquals("\t * Simple summary.", result.get(1));
			assertEquals("\t *", result.get(2));
			assertEquals("\t * @see " + TEST_CONSTRAINT_CLASS, result.get(3));
			assertEquals("\t */", result.get(4));
		}

		@Test
		@DisplayName("should handle multi-line content with per-line comment prefixes")
		void shouldHandleMultiLineContent() {
			final List<String> result = JavaDocSummarizer.formatJavaDoc(
				"First line.\nSecond line.", TEST_CONSTRAINT_CLASS
			);

			assertEquals(6, result.size());
			assertEquals("\t/**", result.get(0));
			assertEquals("\t * First line.", result.get(1));
			assertEquals("\t * Second line.", result.get(2));
			assertEquals("\t *", result.get(3));
			assertEquals("\t * @see " + TEST_CONSTRAINT_CLASS, result.get(4));
			assertEquals("\t */", result.get(5));
		}

		@Test
		@DisplayName("should preserve blank lines as empty comment lines")
		void shouldPreserveBlankLinesAsEmptyComment() {
			final List<String> result = JavaDocSummarizer.formatJavaDoc(
				"Before blank.\n\nAfter blank.", TEST_CONSTRAINT_CLASS
			);

			assertEquals(7, result.size());
			assertEquals("\t/**", result.get(0));
			assertEquals("\t * Before blank.", result.get(1));
			assertEquals("\t *", result.get(2));
			assertEquals("\t * After blank.", result.get(3));
			assertEquals("\t *", result.get(4));
			assertEquals("\t * @see " + TEST_CONSTRAINT_CLASS, result.get(5));
			assertEquals("\t */", result.get(6));
		}

		@Test
		@DisplayName("should produce valid JavaDoc block for empty summary")
		void shouldHandleEmptySummary() {
			final List<String> result = JavaDocSummarizer.formatJavaDoc("", TEST_CONSTRAINT_CLASS);

			assertEquals(5, result.size());
			assertEquals("\t/**", result.get(0));
			// empty string yields a single empty-line entry
			assertEquals("\t *", result.get(1));
			assertEquals("\t *", result.get(2));
			assertEquals("\t * @see " + TEST_CONSTRAINT_CLASS, result.get(3));
			assertEquals("\t */", result.get(4));
		}

		@Test
		@DisplayName("should preserve code block formatting inside summary")
		void shouldPreserveCodeBlockFormatting() {
			final String summary = "Summary paragraph.\n\n```evitaql\nquery(collection(\"Product\"))\n```";

			final List<String> result = JavaDocSummarizer.formatJavaDoc(summary, TEST_CONSTRAINT_CLASS);

			assertEquals(9, result.size());
			assertEquals("\t/**", result.get(0));
			assertEquals("\t * Summary paragraph.", result.get(1));
			assertEquals("\t *", result.get(2));
			assertEquals("\t * ```evitaql", result.get(3));
			assertEquals("\t * query(collection(\"Product\"))", result.get(4));
			assertEquals("\t * ```", result.get(5));
			assertEquals("\t *", result.get(6));
			assertEquals("\t * @see " + TEST_CONSTRAINT_CLASS, result.get(7));
			assertEquals("\t */", result.get(8));
		}

		@Test
		@DisplayName("should always start with /** and end with */")
		void shouldAlwaysStartWithOpenAndEndWithClose() {
			final List<String> result = JavaDocSummarizer.formatJavaDoc("Any content here.", TEST_CONSTRAINT_CLASS);

			assertEquals("\t/**", result.get(0));
			assertEquals("\t */", result.get(result.size() - 1));
		}

		@Test
		@DisplayName("should strip carriage return from Windows-style line endings")
		void shouldStripCarriageReturnFromWindowsLineEndings() {
			final List<String> result = JavaDocSummarizer.formatJavaDoc("Line one.\r\nLine two.", TEST_CONSTRAINT_CLASS);

			assertEquals("\t * Line one.", result.get(1));
			assertEquals("\t * Line two.", result.get(2));
		}

		@Test
		@DisplayName("should escape JavaDoc closing sequence in summary text")
		void shouldEscapeJavaDocClosingSequence() {
			final List<String> result = JavaDocSummarizer.formatJavaDoc(
				"Use pattern a/b */ end.", TEST_CONSTRAINT_CLASS
			);

			// The body lines (excluding opening /** and closing */) must not contain unescaped */
			final List<String> bodyLines = result.subList(1, result.size() - 1);
			assertTrue(
				bodyLines.stream().noneMatch(line -> line.contains("*/")),
				"Body lines must not contain unescaped */ — it breaks JavaDoc structure"
			);
			assertEquals("\t * Use pattern a/b * / end.", result.get(1));
		}
	}

	@Nested
	@DisplayName("Method signature building")
	class MethodSignatureBuildingTest {

		@Test
		@DisplayName("should build signature for no-argument method")
		void shouldBuildSignatureForNoArgMethod() {
			final JavaMethod method = parseMethod(
				"public class Foo { public Bar doStuff() { return null; } }"
			);

			final String signature = JavaDocSummarizer.buildMethodSignature(method);

			assertEquals("Bar doStuff()", signature);
		}

		@Test
		@DisplayName("should build signature for single-parameter method")
		void shouldBuildSignatureForSingleParam() {
			final JavaMethod method = parseMethod(
				"public class Foo { public String greet(String name) { return name; } }"
			);

			final String signature = JavaDocSummarizer.buildMethodSignature(method);

			assertEquals("String greet(String name)", signature);
		}

		@Test
		@DisplayName("should build signature for method with multiple parameters")
		void shouldBuildSignatureForMultipleParams() {
			final JavaMethod method = parseMethod(
				"public class Foo { public int add(int a, int b) { return a + b; } }"
			);

			final String signature = JavaDocSummarizer.buildMethodSignature(method);

			assertEquals("int add(int a, int b)", signature);
		}

		@Test
		@DisplayName("should build signature with varargs suffix for varargs parameter")
		void shouldBuildSignatureForVarArgsParam() {
			final JavaMethod method = parseMethod(
				"public class Foo { public void process(String... items) {} }"
			);

			final String signature = JavaDocSummarizer.buildMethodSignature(method);

			assertEquals("void process(String... items)", signature);
		}
	}

	@Nested
	@DisplayName("User docs link extraction")
	class UserDocsLinkExtractionTest {

		@Test
		@DisplayName("should extract userDocsLink value from @ConstraintDefinition")
		void shouldExtractUserDocsLinkFromConstraintDefinition() {
			final JavaClass clazz = parseClass(
				"import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;\n" +
					"@ConstraintDefinition(userDocsLink = \"/docs/filter/and\")\n" +
					"public class And {}"
			);

			final String link = JavaDocSummarizer.extractUserDocsLink(clazz);

			assertEquals("/docs/filter/and", link);
		}

		@Test
		@DisplayName("should return empty string when class has no @ConstraintDefinition")
		void shouldReturnEmptyStringWhenNoAnnotation() {
			final JavaClass clazz = parseClass("public class Plain {}");

			final String link = JavaDocSummarizer.extractUserDocsLink(clazz);

			assertEquals("", link);
		}

		@Test
		@DisplayName("should return empty string when @ConstraintDefinition has no userDocsLink")
		void shouldReturnEmptyStringWhenUserDocsLinkAbsent() {
			final JavaClass clazz = parseClass(
				"import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;\n" +
					"@ConstraintDefinition\n" +
					"public class NoLink {}"
			);

			final String link = JavaDocSummarizer.extractUserDocsLink(clazz);

			assertEquals("", link);
		}
	}

	@Nested
	@DisplayName("Source hash extraction")
	class SourceHashExtractionTest {

		@Test
		@DisplayName("should extract hash value from @SourceHash annotation")
		void shouldExtractHashFromAnnotation() {
			final JavaMethod method = parseMethod(
				"public class Foo {\n" +
					"  @SourceHash(\"abc123def456abc123def456abc12345\")\n" +
					"  public void myMethod() {}\n" +
					"}"
			);

			final String hash = JavaDocSummarizer.extractExistingSourceHash(method);

			assertEquals("abc123def456abc123def456abc12345", hash);
		}

		@Test
		@DisplayName("should return null when method has no @SourceHash annotation")
		void shouldReturnNullWhenNoAnnotation() {
			final JavaMethod method = parseMethod(
				"public class Foo { public void plain() {} }"
			);

			final String hash = JavaDocSummarizer.extractExistingSourceHash(method);

			assertNull(hash);
		}
	}

	@Nested
	@DisplayName("Source file rewriting")
	class SourceFileRewritingTest {

		@TempDir
		Path tempDir;

		@Test
		@DisplayName("should replace existing JavaDoc with new summary")
		void shouldReplaceExistingJavaDocWithNewSummary() throws IOException {
			final Path file = createSourceFile(
				"package test;\n" +
					"\n" +
					"public class Target {\n" +
					"\n" +
					"\t/**\n" +
					"\t * Old summary.\n" +
					"\t */\n" +
					"\tpublic static Foo foo() { return null; }\n" +
					"}\n"
			);

			final Map<MethodInfo, String> summaries = new LinkedHashMap<>(1);
			summaries.put(
				new MethodInfo(8, "Foo foo()", "constraint doc", TEST_CONSTRAINT_CLASS, "/docs/foo", null),
				"New summary."
			);

			JavaDocSummarizer.rewriteQueryConstraints(file, summaries);

			final String result = Files.readString(file, StandardCharsets.UTF_8);
			assertFalse(result.contains("Old summary."), "Old JavaDoc should be removed");
			assertTrue(result.contains("\t * New summary."), "New summary should be inserted");
		}

		@Test
		@DisplayName("should insert @SourceHash annotation after new JavaDoc")
		void shouldInsertSourceHashAnnotation() throws IOException {
			final Path file = createSourceFile(
				"package test;\n" +
					"\n" +
					"public class Target {\n" +
					"\n" +
					"\t/**\n" +
					"\t * Old summary.\n" +
					"\t */\n" +
					"\tpublic static Foo foo() { return null; }\n" +
					"}\n"
			);

			final String constraintDoc = "constraint doc";
			final String methodSig = "Foo foo()";
			final Map<MethodInfo, String> summaries = new LinkedHashMap<>(1);
			summaries.put(
				new MethodInfo(8, methodSig, constraintDoc, TEST_CONSTRAINT_CLASS, "/docs/foo", null),
				"Summary."
			);

			JavaDocSummarizer.rewriteQueryConstraints(file, summaries);

			final String result = Files.readString(file, StandardCharsets.UTF_8);
			final String expectedHash = JavaDocSummarizer.computeHash(constraintDoc, methodSig);
			assertTrue(
				result.contains("@SourceHash(\"" + expectedHash + "\")"),
				"@SourceHash annotation should be present with computed hash"
			);
		}

		@Test
		@DisplayName("should update existing @SourceHash annotation with new hash")
		void shouldUpdateExistingSourceHashAnnotation() throws IOException {
			final Path file = createSourceFile(
				"package test;\n" +
					"\n" +
					"public class Target {\n" +
					"\n" +
					"\t/**\n" +
					"\t * Old summary.\n" +
					"\t */\n" +
					"\t@SourceHash(\"oldhash1234567890oldhash12345678\")\n" +
					"\tpublic static Foo foo() { return null; }\n" +
					"}\n"
			);

			final String constraintDoc = "new constraint doc";
			final String methodSig = "Foo foo()";
			final Map<MethodInfo, String> summaries = new LinkedHashMap<>(1);
			summaries.put(
				new MethodInfo(9, methodSig, constraintDoc, TEST_CONSTRAINT_CLASS, "/docs/foo", "oldhash1234567890oldhash12345678"),
				"Updated summary."
			);

			JavaDocSummarizer.rewriteQueryConstraints(file, summaries);

			final String result = Files.readString(file, StandardCharsets.UTF_8);
			final String expectedHash = JavaDocSummarizer.computeHash(constraintDoc, methodSig);
			assertFalse(result.contains("oldhash1234567890oldhash12345678"), "Old hash should be removed");
			assertTrue(result.contains("@SourceHash(\"" + expectedHash + "\")"), "New hash should be present");
		}

		@Test
		@DisplayName("should preserve annotations other than @SourceHash between JavaDoc and method")
		void shouldPreserveOtherAnnotations() throws IOException {
			final Path file = createSourceFile(
				"package test;\n" +
					"\n" +
					"public class Target {\n" +
					"\n" +
					"\t/**\n" +
					"\t * Old summary.\n" +
					"\t */\n" +
					"\t@Nullable\n" +
					"\tpublic static Foo foo() { return null; }\n" +
					"}\n"
			);

			final Map<MethodInfo, String> summaries = new LinkedHashMap<>(1);
			summaries.put(
				new MethodInfo(9, "Foo foo()", "constraint doc", TEST_CONSTRAINT_CLASS, "/docs/foo", null),
				"New summary."
			);

			JavaDocSummarizer.rewriteQueryConstraints(file, summaries);

			final String result = Files.readString(file, StandardCharsets.UTF_8);
			assertTrue(result.contains("@Nullable"), "@Nullable annotation should be preserved");
			assertTrue(result.contains("\t * New summary."), "New summary should be present");
		}

		@Test
		@DisplayName("should handle multiple methods processed bottom-to-top")
		void shouldHandleMultipleMethodsBottomToTop() throws IOException {
			final Path file = createSourceFile(
				"package test;\n" +
					"\n" +
					"public class Target {\n" +
					"\n" +
					"\t/**\n" +
					"\t * First old.\n" +
					"\t */\n" +
					"\tpublic static Foo foo() { return null; }\n" +
					"\n" +
					"\t/**\n" +
					"\t * Second old.\n" +
					"\t */\n" +
					"\tpublic static Bar bar() { return null; }\n" +
					"}\n"
			);

			final Map<MethodInfo, String> summaries = new LinkedHashMap<>(2);
			summaries.put(
				new MethodInfo(8, "Foo foo()", "doc1", TEST_CONSTRAINT_CLASS, "/docs/foo", null),
				"First new."
			);
			summaries.put(
				new MethodInfo(13, "Bar bar()", "doc2", TEST_CONSTRAINT_CLASS, "/docs/bar", null),
				"Second new."
			);

			JavaDocSummarizer.rewriteQueryConstraints(file, summaries);

			final String result = Files.readString(file, StandardCharsets.UTF_8);
			assertFalse(result.contains("First old."), "First old JavaDoc should be removed");
			assertFalse(result.contains("Second old."), "Second old JavaDoc should be removed");
			assertTrue(result.contains("\t * First new."), "First new summary should be present");
			assertTrue(result.contains("\t * Second new."), "Second new summary should be present");

			// verify both @SourceHash annotations are inserted
			final String hash1 = JavaDocSummarizer.computeHash("doc1", "Foo foo()");
			final String hash2 = JavaDocSummarizer.computeHash("doc2", "Bar bar()");
			assertTrue(result.contains("@SourceHash(\"" + hash1 + "\")"), "First @SourceHash should be present");
			assertTrue(result.contains("@SourceHash(\"" + hash2 + "\")"), "Second @SourceHash should be present");
		}

		@Test
		@DisplayName("should preserve annotations between JavaDoc closing and @SourceHash")
		void shouldPreserveAnnotationsBetweenJavaDocAndSourceHash() throws IOException {
			final Path file = createSourceFile(
				"package test;\n" +
					"\n" +
					"public class Target {\n" +
					"\n" +
					"\t/**\n" +
					"\t * Old summary.\n" +
					"\t */\n" +
					"\t@Deprecated\n" +
					"\t@SourceHash(\"oldhash1234567890oldhash12345678\")\n" +
					"\tpublic static Foo foo() { return null; }\n" +
					"}\n"
			);

			final Map<MethodInfo, String> summaries = new LinkedHashMap<>(1);
			summaries.put(
				new MethodInfo(10, "Foo foo()", "doc", TEST_CONSTRAINT_CLASS, "/docs/foo", "oldhash1234567890oldhash12345678"),
				"New summary."
			);

			JavaDocSummarizer.rewriteQueryConstraints(file, summaries);

			final String result = Files.readString(file, StandardCharsets.UTF_8);
			assertTrue(
				result.contains("@Deprecated"),
				"@Deprecated between JavaDoc and @SourceHash must be preserved"
			);
			assertTrue(result.contains("\t * New summary."), "New summary should be inserted");
			final String expectedHash = JavaDocSummarizer.computeHash("doc", "Foo foo()");
			assertTrue(
				result.contains("@SourceHash(\"" + expectedHash + "\")"),
				"@SourceHash should be present with updated hash"
			);
		}

		/**
		 * Creates a temporary Java source file in the test's temp directory with the given content.
		 *
		 * @param content the Java source code to write
		 * @return the path to the created file
		 */
		@Nonnull
		private Path createSourceFile(@Nonnull String content) throws IOException {
			final Path file = tempDir.resolve("Target.java");
			Files.writeString(file, content, StandardCharsets.UTF_8);
			return file;
		}
	}

	/**
	 * Parses a Java source snippet via QDox and returns the first declared method.
	 *
	 * @param sourceCode a complete Java class source containing at least one method
	 * @return the first method found in the parsed source
	 */
	@Nonnull
	private static JavaMethod parseMethod(@Nonnull String sourceCode) {
		final JavaProjectBuilder builder = new JavaProjectBuilder();
		builder.addSource(new StringReader(sourceCode));
		final JavaClass clazz = builder.getClasses().iterator().next();
		return clazz.getMethods().get(0);
	}

	/**
	 * Parses a Java source snippet via QDox and returns the first declared class.
	 *
	 * @param sourceCode a complete Java class source
	 * @return the first class found in the parsed source
	 */
	@Nonnull
	private static JavaClass parseClass(@Nonnull String sourceCode) {
		final JavaProjectBuilder builder = new JavaProjectBuilder();
		builder.addSource(new StringReader(sourceCode));
		return builder.getClasses().iterator().next();
	}
}
