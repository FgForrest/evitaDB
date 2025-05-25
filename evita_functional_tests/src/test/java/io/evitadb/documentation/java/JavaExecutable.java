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

package io.evitadb.documentation.java;

import io.evitadb.documentation.UserDocumentationTest;
import io.evitadb.documentation.UserDocumentationTest.CodeSnippet;
import io.evitadb.documentation.java.JavaTestContext.InvocationResult;
import io.evitadb.documentation.java.JavaTestContext.SideEffect;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
	private final @Nullable Path[] requiredResources;
	/**
	 * Contains information about the executable side effects.
	 */
	private final @Nonnull SideEffect sideEffect;
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
		} while (!str.isEmpty());
		return snippets;
	}

	/**
	 * Method reads the {@link #requiredResources} from the file system and returns their contents as a list of
	 * {@link JShell} commands.
	 *
	 * @param jShell            the {@link JShell} instance
	 * @param rootDirectory     the root directory of the documentation
	 * @param requiredResources the list of required resources
	 * @param codeSnippetIndex  the index of all code snippets in the documentation
	 * @return the list of {@link JShell} commands
	 */
	@Nonnull
	static List<String> composeRequiredBlocks(
		@Nonnull JShell jShell,
		@Nonnull Path rootDirectory,
		@Nonnull Path[] requiredResources,
		@Nonnull Map<Path, CodeSnippet> codeSnippetIndex
	) {
		final List<String> requiredSnippet = new LinkedList<>();
		for (Path require : requiredResources) {
			final CodeSnippet requiredScript = codeSnippetIndex.get(require);
			// if the code snippet is not found in the index, it's read from the file system
			if (requiredScript == null) {
				requiredSnippet.addAll(toJavaSnippets(jShell, UserDocumentationTest.readFileOrThrowException(rootDirectory.resolve(require))));
			} else {
				final Executable executable = requiredScript.executableLambda();
				Assert.isTrue(executable instanceof JavaExecutable, "Java example may require only Java executables!");
				requiredSnippet.addAll(
					((JavaExecutable) executable).getSnippets()
				);
			}
		}
		return requiredSnippet;
	}

	/**
	 * Method creates list of source code snippets that could be passed to {@link JShell} instance for compilation and
	 * execution. If the code snippet declares another code snippet via {@link #requiredResources} as predecessor,
	 * the executable of such predecessor code snippet is prepended to the list of snippets. If such block is not found
	 * within the same documentation file, it's read from the file system directly.
	 */
	@Nonnull
	private static List<String> composeCodeBlockWithRequiredBlocks(
		@Nonnull JShell jShell,
		@Nonnull String sourceCode,
		@Nonnull Path rootDirectory,
		@Nullable Path[] requiredResources,
		@Nonnull Map<Path, CodeSnippet> codeSnippetIndex
	) {
		if (ArrayUtils.isEmpty(requiredResources)) {
			// simply return contents of the code snippet as result
			return toJavaSnippets(jShell, sourceCode);
		} else {
			final List<String> requiredSnippet = composeRequiredBlocks(jShell, rootDirectory, requiredResources, codeSnippetIndex);
			// now combine both required and dependent snippets together
			return Stream.concat(
				requiredSnippet.stream(),
				toJavaSnippets(jShell, sourceCode).stream()
			).toList();
		}
	}

	@Override
	public void execute() throws Throwable {
		final JavaTestContext javaTestContext = this.testContextAccessor.get();

		// the code block must be successfully compiled and executed without an error
		// to mark it as ok
		final InvocationResult result = javaTestContext.executeJShellCommands(getSnippets(), this.sideEffect);

		if (result.exception() != null) {
			throw result.exception();
		}
	}

	/**
	 * Parses the {@link #sourceContent} to a list of {@link JShell} commands to execute.
	 */
	@Nonnull
	public List<String> getSnippets() {
		if (this.parsedSnippets == null) {
			this.parsedSnippets = composeCodeBlockWithRequiredBlocks(
				this.testContextAccessor.get().getJShell(), this.sourceContent, getRootDirectory(), this.requiredResources, this.codeSnippetIndex
			);
		}
		return this.parsedSnippets;
	}

}
