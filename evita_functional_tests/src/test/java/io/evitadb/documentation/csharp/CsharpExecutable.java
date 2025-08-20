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

package io.evitadb.documentation.csharp;

import io.evitadb.documentation.UserDocumentationTest;
import io.evitadb.documentation.UserDocumentationTest.CodeSnippet;
import io.evitadb.documentation.UserDocumentationTest.OutputSnippet;
import io.evitadb.documentation.java.JavaExecutable;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import jdk.jshell.Snippet;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.documentation.UserDocumentationTest.resolveSiblingWithDifferentExtension;
import static java.util.Optional.ofNullable;

/**
 * The implementation of the C# source code dynamic test verifying single C# example from the documentation.
 * C# code needs to be compilable and executable without trowing an exception. Results of the code are verified against
 * expected `evitaql` result snippets.
 *
 * @author Tomáš Pozler, 2023
 */
@RequiredArgsConstructor
public class CsharpExecutable implements Executable, EvitaTestSupport {
	/**
	 * Provides access to the {@link CsharpTestContext} instance.
	 */
	private final @Nonnull Supplier<CsharpTestContext> testContextAccessor;
	/**
	 * Contains the tested C# code.
	 */
	private final @Nonnull String sourceContent;
	/**
	 * Contains root directory.
	 */
	private final @Nonnull Path rootDirectory;
	/**
	 * Contains the path of the source file (used when output files are generated and the MarkDown file content
	 * is verified.
	 */
	private final @Nullable Path resource;
	/**
	 * Contains paths of C# files that needs to be executed prior to {@link #sourceContent}
	 */

	private final @Nullable Path[] requires;
	/**
	 * Contains index of all (so-far) identified code snippets in this document so that we can reuse their source codes.
	 */
	private final @Nonnull Map<Path, CodeSnippet> codeSnippetIndex;
	/**
	 * Temporary variable contains already parsed {@link #sourceContent} into a separate list of C# commands.
	 */
	private List<String> parsedSnippets;
	/**
	 * Contains reference to the output snippet bound to this executable.
	 */
	private final @Nullable List<OutputSnippet> outputSnippet;

	/**
	 * Static initializer that removes any existing C# query validator, if it exists. It's necessary to ensure that
	 * the tests are using the latest version of the validator. Since it's the static initializer, it's executed only
	 * once per JVM run.
	 */
	static {
		CShell.clearDownloadedValidator();
	}

	/**
	 * Method executes the list of {@link Snippet} in passed {@link CShell} instance a verifies that the execution
	 * finished without an error.
	 */
	void executeCShellCommands(@Nonnull CShell cShell, @Nonnull List<String> snippets, @Nullable OutputSnippet outputSnippet) {
		final List<RuntimeException> exceptions = new LinkedList<>();

		// iterate over snippets and execute them
		for (String snippet : snippets) {
			try {
				final String outputFormat = ofNullable(outputSnippet).map(OutputSnippet::forFormat).orElse("md");
				final Path assertSource = outputSnippet == null ?
					resolveSiblingWithDifferentExtension(this.resource, outputFormat).normalize() :
					outputSnippet.path().normalize();

				final String relativePath = assertSource.toString().substring(this.rootDirectory.normalize().toString().length());
				final String sourceVariable = outputSnippet == null || outputSnippet.sourceVariable() == null ? null : outputSnippet.sourceVariable();
				final String output = cShell.evaluate(snippet, outputFormat, sourceVariable);
				if (outputSnippet != null) {
					final String expectedOutput = UserDocumentationTest.readFileOrThrowException(outputSnippet.path());
					Assertions.assertEquals(expectedOutput.trim(), output.trim());
					System.out.println("Markdown snippet `" + relativePath + "` contents verified OK (C#). \uD83D\uDE0A");
				}
			} catch (RuntimeException e) {
				exceptions.add(e);
			}
		}
		if (!exceptions.isEmpty()) {
			throw new RuntimeException(
				exceptions.stream().map(Throwable::getMessage).reduce("", (a, b) -> a + "\n" + b)
			);
		}
	}

	/**
	 * Method executes the collected code snippets via the {@link CShell} instance.
	 * @throws Throwable any exceptions thrown during the execution of the C# caused by query validator
	 */
	@Override
	public void execute() throws Throwable {
		final CShell cShell = this.testContextAccessor.get().getCshell();
		for (OutputSnippet snippet : this.outputSnippet) {
			executeCShellCommands(cShell, getSnippets(), snippet);
		}
	}

	/**
	 * Parses the {@link #sourceContent} to a list of {@link CShell} commands to execute.
	 */
	@Nonnull
	public List<String> getSnippets() {
		if (this.parsedSnippets == null) {
			this.parsedSnippets = composeCodeBlockWithRequiredBlocks(
				this.sourceContent, this.codeSnippetIndex
			);
		}
		return this.parsedSnippets;
	}

	/**
	 * Method creates list of source code snippets that could be passed to {@link CShell} instance for compilation and
	 * execution. If the code snippet declares another code snippet via {@link #requires} as predecessor,
	 * the executable of such predecessor code snippet is prepended to the list of snippets. If such block is not found
	 * within the same documentation file, it's read from the file system directly. Since evitaDB instance may be required
	 * for some tests, it's allowed to require Java code snippets for starting it up. Also, since the required blocks are
	 * executed here in Java code, it's the only allowed language for the required blocks.
	 */
	@Nonnull
	private List<String> composeCodeBlockWithRequiredBlocks(
		@Nonnull String sourceCode,
		@Nonnull Map<Path, CodeSnippet> codeSnippetIndex
	) {
		if (ArrayUtils.isEmpty(this.requires)) {
			// simply return contents of the code snippet as result
			return List.of(sourceCode);
		} else {
			final List<String> requiredSnippet = new LinkedList<>();
			for (Path require : this.requires) {
				final CodeSnippet requiredScript = codeSnippetIndex.get(require);
				// if the code snippet is not found in the index, it's read from the file system
				if (requiredScript == null) {
					requiredSnippet.add(UserDocumentationTest.readFileOrThrowException(getRootDirectory().resolve(require)));
				} else {
					final Executable executable = requiredScript.executableLambda();
					Assert.isTrue(executable instanceof JavaExecutable, "C# example may require only Java executables!");
					requiredSnippet.addAll(
						((JavaExecutable) executable).getSnippets()
					);
				}
			}
			// now combine both required and dependent snippets together
			return Stream.concat(
				requiredSnippet.stream(),
				Stream.of(sourceCode)
			).toList();
		}
	}
}
