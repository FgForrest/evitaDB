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

package io.evitadb.documentation.java;

import io.evitadb.core.Evita;
import io.evitadb.documentation.UserDocumentationTest;
import io.evitadb.documentation.UserDocumentationTest.CodeSnippet;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.Snippet.Status;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;
import jdk.jshell.VarSnippet;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The implementation of the Java source code dynamic test verifying single Java example from the documentation.
 * Java code needs to be compilable and executable without trowing an exception. Results of the code are not verified.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class JavaExecutable implements Executable, EvitaTestSupport {
	/**
	 * Provides access to the {@link JavaTestContext} instance.
	 */
	private final @Nonnull Supplier<JavaTestContext> testContextAccessor;
	/**
	 * Contains the tested Java code.
	 */
	private final @Nonnull String sourceContent;
	/**
	 * Contains paths of Java files that needs to be executed prior to {@link #sourceContent}
	 */
	private final @Nullable Path[] requires;
	/**
	 * Contains index of all (so-far) identified code snippets in this document so that we can reuse their source codes.
	 */
	private final @Nonnull Map<Path, CodeSnippet> codeSnippetIndex;
	/**
	 * Temporary variable contains already parsed {@link #sourceContent} into a separate list of Java commands.
	 */
	private List<String> parsedSnippets;

	/**
	 * Method parses the `sourceCode` to separate {@link Snippet} that are executable by the JShell.
	 */
	@Nonnull
	static List<String> toJavaSnippets(@Nonnull JShell jShell, @Nonnull String sourceCode) {
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
	static InvocationResult executeJShellCommands(@Nonnull JShell jShell, @Nonnull List<String> snippets) {
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

	@Override
	public void execute() throws Throwable {
		final JShell jShell = testContextAccessor.get().getJShell();
		// the code block must be successfully compiled and executed without an error
		// to mark it as ok
		final InvocationResult result = executeJShellCommands(jShell, getSnippets());

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

	/**
	 * Parses the {@link #sourceContent} to a list of {@link JShell} commands to execute.
	 */
	@Nonnull
	public List<String> getSnippets() {
		if (parsedSnippets == null) {
			parsedSnippets = composeCodeBlockWithRequiredBlocks(
				testContextAccessor.get().getJShell(), sourceContent, codeSnippetIndex
			);
		}
		return parsedSnippets;
	}

	/**
	 * Method creates list of source code snippets that could be passed to {@link JShell} instance for compilation and
	 * execution. If the code snippet declares another code snippet via {@link #requires} as predecessor,
	 * the executable of such predecessor code snippet is prepended to the list of snippets. If such block is not found
	 * within the same documentation file, it's read from the file system directly.
	 */
	@Nonnull
	private List<String> composeCodeBlockWithRequiredBlocks(
		@Nonnull JShell jShell,
		@Nonnull String sourceCode,
		@Nonnull Map<Path, CodeSnippet> codeSnippetIndex
	) {
		if (ArrayUtils.isEmpty(requires)) {
			// simply return contents of the code snippet as result
			return toJavaSnippets(jShell, sourceCode);
		} else {
			final List<String> requiredSnippet = new LinkedList<>();
			for (Path require : requires) {
				final CodeSnippet requiredScript = codeSnippetIndex.get(require);
				// if the code snippet is not found in the index, it's read from the file system
				if (requiredScript == null) {
					requiredSnippet.addAll(toJavaSnippets(jShell, UserDocumentationTest.readFileOrThrowException(getRootDirectory().resolve(require))));
				} else {
					final Executable executable = requiredScript.executableLambda();
					Assert.isTrue(executable instanceof JavaExecutable, "Java example may require only Java executables!");
					requiredSnippet.addAll(
						((JavaExecutable) executable).getSnippets()
					);
				}
			}
			// now combine both required and dependent snippets together
			return Stream.concat(
				requiredSnippet.stream(),
				toJavaSnippets(jShell, sourceCode).stream()
			).toList();
		}
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
