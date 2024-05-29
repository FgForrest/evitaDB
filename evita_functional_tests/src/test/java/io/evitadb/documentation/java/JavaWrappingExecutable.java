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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.documentation.java;

import io.evitadb.documentation.UserDocumentationTest.CodeSnippet;
import io.evitadb.documentation.java.JavaExecutable.InvocationResult;
import io.evitadb.test.EvitaTestSupport;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.VarSnippet;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.evitadb.documentation.java.JavaExecutable.clearOnTearDown;
import static io.evitadb.documentation.java.JavaExecutable.composeRequiredBlocks;
import static io.evitadb.documentation.java.JavaExecutable.executeJShellCommands;

/**
 * This class allows to execute some Java code before and after the actual test. The code is executed in the same
 * process as the test itself, so it can be used to prepare the environment for the test.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class JavaWrappingExecutable implements Executable, EvitaTestSupport {
	/**
	 * Provides access to the {@link JavaTestContext} instance.
	 */
	private final @Nonnull Supplier<JavaTestContext> testContextAccessor;
	/**
	 * Contains the executable that should be invoked after all required resources are executed, and before they are
	 * torn down.
	 */
	@Nonnull private final Executable delegate;
	/**
	 * Contains paths of Java files that needs to be executed prior to {@link #delegate}
	 */
	private final @Nullable Path[] requiredResources;
	/**
	 * Contains index of all (so-far) identified code snippets in this document so that we can reuse their source codes.
	 */
	private final @Nonnull Map<Path, CodeSnippet> codeSnippetIndex;

	@Override
	public void execute() throws Throwable {
		final JShell jShell = testContextAccessor.get().getJShell();
		final InvocationResult result = executeJShellCommands(
			jShell,
			composeRequiredBlocks(jShell, getRootDirectory(), requiredResources, codeSnippetIndex)
		);

		try {
			if (result.exception() == null) {
				delegate.execute();
			}
		} finally {
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
							"String evitaStoragePathToClear = Evita.class.isInstance(" + varSnippet.name() + ") ? " +
								"Evita.class.cast(" + varSnippet.name() + ").getConfiguration().storage()" +
								".storageDirectory().toAbsolutePath().toString() : \"\";\n"
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
	}

}
