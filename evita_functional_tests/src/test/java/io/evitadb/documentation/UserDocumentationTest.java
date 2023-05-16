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

package io.evitadb.documentation;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.Snippet.Status;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;
import jdk.jshell.VarSnippet;
import jdk.jshell.execution.LocalExecutionControlProvider;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * This test class generates the dynamic tests that compile and execute all source code examples found in user
 * documentation MarkDown files. The test searches for all occurrences of:
 *
 * 1. ``` java ``` block
 * 2. <SourceCodeTabs> block
 *
 * The test generates for each of such block separate {@link DynamicTest} instance enveloped in {@link DynamicNode}
 * that wraps all tests for the same MarkDown file. The tests use {@link JShell} to compile and execute the snippets.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class UserDocumentationTest implements EvitaTestSupport {
	/**
	 * Pattern for searching for ``` java ``` blocks.
	 */
	private static final Pattern SOURCE_CODE_PATTERN = Pattern.compile(
		"```(.*?)\n(.+?)```",
		Pattern.DOTALL | Pattern.MULTILINE
	);
	/**
	 * Pattern for searching for <SourceCodeTabs> blocks.
	 */
	private static final Pattern SOURCE_CODE_TABS_PATTERN = Pattern.compile(
		"<SourceCodeTabs\\s*(requires=\"(.*?)\")?>\\s*\\[.*?\\]\\((.*?)\\)\\s*</SourceCodeTabs>",
		Pattern.DOTALL | Pattern.MULTILINE
	);
	/**
	 * Field contains contents of the `META-INF/documentation/imports.java` file that contains all imports required
	 * by the tests (code snippets).
	 */
	private static final String STATIC_IMPORT;
	/**
	 * Field containing the relative directory path to the user documentation.
	 */
	private static final String DOCS_ROOT = "docs/user/";
	/**
	 * Field contains initialized Java templates for various language formats located in `META-INF/documentation` folder
	 * on classpath.
	 */
	private static final Map<String, List<String>> LANGUAGE_TEMPLATES = new HashMap<>();
	/**
	 * Field contains list of all languages that are not tested by this class.
	 */
	private static final Set<String> NOT_TESTED_LANGUAGES = new HashSet<>();
	/**
	 * Regex pattern for replacing a placeholder in the Java template.
	 */
	private static final Pattern THE_QUERY_REPLACEMENT = Pattern.compile("^(\\s*)#QUERY#.*$", Pattern.DOTALL);

	static {
		try {
			final ClassLoader classLoader = UserDocumentationTest.class.getClassLoader();
			STATIC_IMPORT = IOUtils.toString(classLoader.getResource("META-INF/documentation/imports.java"), StandardCharsets.UTF_8);
			try (final InputStream is = classLoader.getResource("META-INF/documentation/evitaql.java").openStream()) {
				LANGUAGE_TEMPLATES.put("evitaql", IOUtils.readLines(is, StandardCharsets.UTF_8));
			}
			NOT_TESTED_LANGUAGES.add("evitaql-syntax");
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Method parses the `sourceCode` to separate {@link Snippet} that are executable by the JShell.
	 */
	@Nonnull
	private static List<String> toJavaSnippets(@Nonnull JShell jShell, @Nonnull String sourceCode) {
		final SourceCodeAnalysis sca = jShell.sourceCodeAnalysis();
		final List<String> snippets = new LinkedList<>();
		String str = sourceCode;
		do {
			CompletionInfo info = sca.analyzeCompletion(str);
			snippets.add(info.source());
			str = info.remaining();
		} while (str.length() > 0);
		return snippets;
	}

	/**
	 * Method executes the list of {@link Snippet} in passed {@link JShell} instance a verifies that the execution
	 * finished without an error.
	 */
	@Nonnull
	private static InvocationResult executeJShellCommands(@Nonnull JShell jShell, @Nonnull List<String> snippets) {
		final StringBuilder errorBuilder = new StringBuilder(512);
		AssertionError error = null;
		final ArrayList<Snippet> executedSnippets = new ArrayList<>(snippets.size() << 1);

		execution:
		for (String snippet : snippets) {
			final List<SnippetEvent> events = jShell.eval(snippet);
			for (SnippetEvent event : events) {
				if (!event.status().isActive()) {
					errorBuilder.append(jShell.diagnostics(event.snippet())
							.map(it -> "\n- [" + it.getStartPosition() + "-" + it.getEndPosition() + "] " + it.getMessage(Locale.ENGLISH))
							.collect(Collectors.joining()))
						.append("\nSource:\n")
						.append(event.snippet().source());
					break execution;
				} else if (event.exception() != null) {
					errorBuilder.append(
						printException(event.exception(), new HashSet<>())
					).append("\n");
					if (event.status() == Status.VALID) {
						jShell.drop(event.snippet());
					}
					break execution;
				} else {
					executedSnippets.add(event.snippet());
				}
			}
		}

		if (!errorBuilder.isEmpty()) {
			error = new AssertionError(errorBuilder.toString());
		}

		return new InvocationResult(
			executedSnippets,
			error
		);
	}

	/**
	 * Prints exception messages to the root cause.
	 */
	@Nonnull
	private static String printException(@Nonnull Throwable exception, @Nonnull Set<Throwable> visitedExceptions) {
		if (exception.getCause() == null) {
			return exception.getMessage();
		} else {
			visitedExceptions.add(exception);
			if (!visitedExceptions.contains(exception.getCause())) {
				return exception.getMessage() + "\n" + printException(exception.getCause(), visitedExceptions);
			} else {
				return exception.getMessage();
			}
		}
	}

	/**
	 * Method returns already initialized {@link JShell} from the passed {@link AtomicReference} or creates new instance
	 * initializes it with classpath and imports and stores it into the reference.
	 *
	 * The {@link JShell} instance is reused between tests to speed them up. The {@link JShell} with full classpath
	 * initialization takes a noticeable time we could avoid.
	 */
	@Nonnull
	private static JShell getOrInitJShell(@Nonnull AtomicReference<JShell> jShellReference) {
		JShell jShell = jShellReference.get();
		if (jShell == null) {
			jShell = JShell.builder()
				// this is faster because JVM is not forked for each test
				.executionEngine(new LocalExecutionControlProvider(), Collections.emptyMap())
				.out(System.out)
				.err(System.err)
				.build();
			// we copy the entire classpath of this test to the JShell instance
			Arrays.stream(System.getProperty("java.class.path").split(":"))
				.forEach(jShell::addToClasspath);
			jShellReference.set(jShell);
			// and now pre initialize all necessary imports
			executeJShellCommands(jShell, toJavaSnippets(jShell, STATIC_IMPORT));
		}
		return jShell;
	}

	/**
	 * Method clears the tear down snippet and deletes {@link Evita} directory if it was accessed in
	 * test.
	 */
	private static void clearOnTearDown(@Nonnull JShell jShell, @Nonnull Snippet tearDownSnippet) {
		if (tearDownSnippet instanceof VarSnippet pathDeclaration && "evitaStoragePathToClear".equals(pathDeclaration.name())) {
			final String stringValue = jShell.varValue(pathDeclaration);
			final String pathToClear = stringValue.substring(1, stringValue.length() - 1);
			if (!pathToClear.isBlank()) {
				// finally we clear the test directory itself, so that each test starts with empty one
				try {
					FileUtils.deleteDirectory(Path.of(pathToClear).toFile());
				} catch (IOException ex) {
					// ignore
				}
			}
		}
		jShell.drop(tearDownSnippet);
	}

	/**
	 * This test scans all MarkDown files in the user documentation directory and generates {@link DynamicTest}
	 * instances for each source code block found. Tests are organized in nodes that represents the file they're part
	 * of. The first test of the document always initializes the JShell instance, the last test finalizes the JShell
	 * instance.
	 */
	@DisplayName("User documentation")
	@Tag(DOCUMENTATION_TEST)
	@TestFactory
	Stream<DynamicNode> testDocumentation() throws IOException {
		try (final Stream<Path> walker = Files.walk(getRootDirectory().resolve(DOCS_ROOT))) {
			final List<DynamicNode> nodes = walker
				.filter(path -> path.toString().endsWith(".md"))
				.map(it -> {
					final List<DynamicTest> tests = this.createTests(it);
					if (tests.isEmpty()) {
						return null;
					} else {
						return (DynamicNode) DynamicContainer.dynamicContainer(
							"File: `" + it.getFileName() + "`",
							it.toUri(),
							tests.stream()
						);
					}
				})
				.filter(Objects::nonNull)
				.toList();
			return nodes.stream();
		}
	}

	/**
	 * The test is disabled and should be used only for "debugging" snippets in certain documentation file.
	 */
	@DisplayName("Debug user documentation")
	@TestFactory
	@Tag(DOCUMENTATION_TEST)
	@Disabled
	Stream<DynamicTest> testSingleFileDocumentation() {
		return this.createTests(getRootDirectory().resolve("docs/user/en/query/filtering/hierarchy.md")).stream();
	}

	/**
	 * Method creates list of {@link DynamicTest} instances for all code blocks in the file of given {@link Path}.
	 * Method returns empty collection if no code block is found.
	 */
	@Nonnull
	private List<DynamicTest> createTests(@Nonnull Path path) {
		// find code blocks
		final List<CodeSnippet> codeSnippets = extractJavaCodeBlocks(path);
		// and create an index for them for resolving the dependencies
		final Map<String, CodeSnippet> codeSnippetIndex = codeSnippets
			.stream()
			.collect(
				Collectors.toMap(
					CodeSnippet::path,
					Function.identity()
				)
			);

		// create tests
		final AtomicReference<JShell> jShellReference = new AtomicReference<>();
		final Stream<DynamicTest> tests = codeSnippets
			.stream()
			.map(
				codeSnippet ->
					dynamicTest(
						// use the name from the code snippet for the name of the test
						codeSnippet.name(),
						// if the code snippet refers to an external file, link it here, so that clicks work in IDE
						ofNullable(codeSnippet.path()).map(it -> Path.of(it).toUri()).orElse(null),
						// then execute the snippet
						() -> {
							final JShell jShell = getOrInitJShell(jShellReference);
							// the code block must be successfully compiled and executed without an error
							// to mark it as ok
							final InvocationResult result = executeJShellCommands(
								jShell,
								composeCodeBlockWithRequiredBlocks(jShell, codeSnippet, codeSnippetIndex)
							);

							// clean up - we travel from the most recent (last) snippet to the first
							final List<Snippet> snippets = result.snippets();
							for (int i = snippets.size() - 1; i >= 0; i--) {
								final Snippet snippet = snippets.get(i);
								// if the snippet declared an AutoCloseable variable, we need to close it
								if (snippet instanceof VarSnippet varSnippet) {
									// there is no way how to get the reference of the variable - so the clean up
									// must be performed by another snippet
									executeJShellCommands(
										jShell,
										Arrays.asList(
											// instanceof / cast throws a compiler exception, so that we need to
											// work around it by runtime evaluation
											"if (AutoCloseable.class.isInstance(" + varSnippet.name() + ")) {\n\t" +
												"AutoCloseable.class.cast(" + varSnippet.name() + ").close();\n" +
												"}\n",
											// retrieve the folder location
											"String evitaStoragePathToClear = Evita.class.isInstance(" + varSnippet.name() + ") ? Evita.class.cast(" + varSnippet.name() + ").getConfiguration().storage().storageDirectory().toAbsolutePath().toString() : \"\";\n"
										)
									)
										.snippets()
										.forEach(it -> clearOnTearDown(jShell, it));
								}
								// each snippet is "dropped" by the JShell instance (undone)
								jShell.drop(snippet);
							}

							if (result.exception() != null) {
								throw result.exception();
							}
						}
					)
			);

		// return tests if some code blocks were found
		return codeSnippets.isEmpty() ?
			Collections.emptyList() :
			Stream.of(
					// always start with shell instance initialization
					Stream.of(
						dynamicTest(
							"Init JShell",
							() -> getOrInitJShell(jShellReference)
						)
					),
					// execute tests in between
					tests,
					// always finish with shell instance tear down
					Stream.of(
						dynamicTest(
							"Destroy JShell",
							() -> jShellReference.get().close()
						)
					)
				)
				.flatMap(Function.identity())
				.toList();
	}

	/**
	 * Method creates list of source code snippets that could be passed to {@link JShell} instance for compilation and
	 * execution. If the code snippet declares another code snippet via {@link CodeSnippet#requires()} as predecessor,
	 * the content of such predecessor code snippet is prepended to the list of snippets. If such block is not found
	 * within the same documentation file, it's read from the file system directly.
	 */
	@Nonnull
	private List<String> composeCodeBlockWithRequiredBlocks(
		@Nonnull JShell jShell,
		@Nonnull CodeSnippet codeSnippet,
		@Nonnull Map<String, CodeSnippet> codeSnippetIndex
	) {
		final String[] requires = codeSnippet.requires();
		if (ArrayUtils.isEmpty(requires)) {
			// simply return contents of the code snippet as result
			return toJavaSnippets(jShell, codeSnippet.content());
		} else {
			final List<String> requiredSnippet = new LinkedList<>();
			for (String require : requires) {
				final CodeSnippet requiredScript = codeSnippetIndex.get(require);
				// if the code snippet is not found in the index, it's read from the file system
				if (requiredScript == null) {
					try {
						requiredSnippet.addAll(toJavaSnippets(jShell, Files.readString(getRootDirectory().resolve(require))));
					} catch (IOException e) {
						Assertions.fail(codeSnippet.name() + " requires `" + require + "` which was not found!", e);
						return Collections.emptyList();
					}
				} else {
					requiredSnippet.addAll(composeCodeBlockWithRequiredBlocks(jShell, requiredScript, codeSnippetIndex));
				}
			}
			// now combine both required and dependent snippets together
			return Stream.concat(
				requiredSnippet.stream(),
				toJavaSnippets(jShell, codeSnippet.content()).stream()
			).toList();
		}
	}

	/**
	 * Method extracts code blocks from the MarkDown file.
	 */
	@Nonnull
	private List<CodeSnippet> extractJavaCodeBlocks(@Nonnull Path path) {
		try {
			final String fileContent = Files.readString(path);
			final AtomicInteger index = new AtomicInteger();
			final List<CodeSnippet> result = new LinkedList<>();

			final Matcher sourceCodeMatcher = SOURCE_CODE_PATTERN.matcher(fileContent);
			while (sourceCodeMatcher.find()) {
				final String format = sourceCodeMatcher.group(1);
				if (!NOT_TESTED_LANGUAGES.contains(format)) {
					result.add(
						new CodeSnippet(
							"Example #" + index.incrementAndGet(),
							format,
							null,
							convertToRunnable(
								format,
								sourceCodeMatcher.group(2),
								null
							),
							null
						)
					);
				}
			}

			final Matcher sourceCodeTabsMatcher = SOURCE_CODE_TABS_PATTERN.matcher(fileContent);
			while (sourceCodeTabsMatcher.find()) {
				final Path referencedFile = getRootDirectory().resolve(sourceCodeTabsMatcher.group(3));
				final String format = ofNullable(referencedFile.getFileName().toString())
					.filter(f -> f.contains("."))
					.map(f -> f.substring(f.lastIndexOf('.') + 1))
					.orElseThrow(() -> new IllegalArgumentException("File name must contain `.`!"));
				if (!NOT_TESTED_LANGUAGES.contains(format)) {
					result.add(
						new CodeSnippet(
							"Example `" + referencedFile.getFileName() + "`",
							format,
							referencedFile.toAbsolutePath().toString(),
							convertToRunnable(
								format,
								Files.readString(referencedFile),
								referencedFile
							),
							ofNullable(sourceCodeTabsMatcher.group(2))
								.map(
									requires -> Arrays.stream(requires.split(","))
										.filter(it -> !it.isBlank())
										.map(it -> getRootDirectory().resolve(it).toAbsolutePath().toString())
										.toArray(String[]::new)
								)
								.orElse(null)
						)
					);
				}
			}
			return result;
		} catch (IOException e) {
			Assertions.fail(e);
			return Collections.emptyList();
		}
	}

	/**
	 * Method converts the source format to the Java executable that can be run in {@link JShell}.
	 *
	 * @param sourceFormat  the file format of the source code
	 * @param sourceContent the content of the source code
	 * @return the source code translated from source code to Java executable code
	 */
	@Nonnull
	private static String convertToRunnable(@Nonnull String sourceFormat, @Nonnull String sourceContent, @Nullable Path resource) {
		switch (sourceFormat) {
			case "java" -> { return sourceContent; }
			case "evitaql" -> {
				final Query theQuery;
				try {
					theQuery = DefaultQueryParser.getInstance().parseQueryUnsafe(sourceContent);
				} catch (Exception ex) {
					Assertions.fail(
						"Failed to parse query " +
							ofNullable(resource).map(it -> "from resource " + it).orElse("") + ": \n" +
							sourceContent,
						ex
					);
					throw ex;
				}
				final List<String> sourceTemplate = LANGUAGE_TEMPLATES.get(sourceFormat);
				Assertions.assertNotNull(sourceTemplate, "Failed to find language template for " + sourceFormat);
				final String result = sourceTemplate
					.stream()
					.map(theLine -> {
						final Matcher replacementMatcher = THE_QUERY_REPLACEMENT.matcher(theLine);
						if (replacementMatcher.matches()) {
							return JavaPrettyPrintingVisitor.toString(theQuery, "\t", replacementMatcher.group(1));
						} else {
							return theLine;
						}
					})
					.collect(Collectors.joining("\n"));
				return result;
			}
			default -> throw new UnsupportedOperationException("Unsupported file format: " + sourceFormat);
		}
	}

	/**
	 * Record represents a code block occurrence found in the MarkDown document.
	 *
	 * @param name     title of the code snippet allowing its identification in the document
	 * @param format   source format (language) of the example
	 * @param path     path to the external file containing the code snippet
	 * @param content  content of the code snippet
	 * @param requires reference to the required predecessor code block that must be executed before this one
	 */
	private record CodeSnippet(
		@Nonnull String name,
		@Nonnull String format,
		@Nullable String path,
		@Nonnull String content,
		@Nullable String[] requires
	) {
	}

	/**
	 * Record contains result of the Java code execution.
	 */
	private record InvocationResult(
		@Nonnull List<Snippet> snippets,
		@Nullable Error exception
	) {
	}

}