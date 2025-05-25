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

import io.evitadb.documentation.UserDocumentationTest.CodeSnippet;
import io.evitadb.documentation.java.JavaTestContext.InvocationResult;
import io.evitadb.documentation.java.JavaTestContext.SideEffect;
import io.evitadb.test.EvitaTestSupport;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import static io.evitadb.documentation.java.JavaExecutable.composeRequiredBlocks;

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
	 * Contains information about the executable side effects.
	 */
	private final @Nonnull SideEffect sideEffect;
	/**
	 * Contains index of all (so-far) identified code snippets in this document so that we can reuse their source codes.
	 */
	private final @Nonnull Map<Path, CodeSnippet> codeSnippetIndex;

	@Override
	public void execute() throws Throwable {
		final JavaTestContext javaTestContext = this.testContextAccessor.get();

		final InvocationResult result = javaTestContext.executeJShellCommands(
			composeRequiredBlocks(javaTestContext.getJShell(), getRootDirectory(), this.requiredResources, this.codeSnippetIndex),
			this.sideEffect,
			invocationResult -> {
				if (invocationResult.exception() == null) {
					try {
						this.delegate.execute();
					} catch (Throwable e) {
						return new InvocationResult(
							invocationResult.snippets(),
							new RuntimeException(e)
						);
					}
				}
				return invocationResult;
			}
		);

		if (result.exception() != null) {
			throw result.exception();
		}
	}

}
