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

import io.evitadb.documentation.DocumentationProfile;
import io.evitadb.documentation.TestContext;
import io.evitadb.documentation.UserDocumentationTest;
import io.evitadb.documentation.java.JavaExecutable.InvocationResult;
import jdk.jshell.JShell;
import jdk.jshell.execution.LocalExecutionControlProvider;
import lombok.Getter;
import org.apache.commons.io.IOUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.documentation.java.JavaExecutable.executeJShellCommands;
import static io.evitadb.documentation.java.JavaExecutable.toJavaSnippets;

/**
 * Context creates new {@link JShell} instance, initializes it with classpath and imports and stores it into
 * the internal field.
 *
 * The {@link JShell} instance is reused between tests to speed them up. The {@link JShell} with full classpath
 * initialization takes a noticeable time we could avoid.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class JavaTestContext implements TestContext {
	/**
	 * Field contains contents of the `META-INF/documentation/imports.java` file that contains all imports required
	 * by the tests (code snippets).
	 */
	private static final String STATIC_IMPORT;

	static {
		try {
			final ClassLoader classLoader = UserDocumentationTest.class.getClassLoader();
			STATIC_IMPORT = IOUtils.toString(classLoader.getResource("META-INF/documentation/imports.java"), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Initialized jShell instance.
	 */
	@Getter
	private final JShell jShell;

	public JavaTestContext(@Nonnull DocumentationProfile profile) {
		this.jShell = JShell.builder()
			// this is faster because JVM is not forked for each test
			.executionEngine(new LocalExecutionControlProvider(), Collections.emptyMap())
			.out(System.out)
			.err(System.err)
			.build();
		// we copy the entire classpath of this test to the JShell instance
		Arrays.stream(System.getProperty("java.class.path").split(":"))
			.forEach(jShell::addToClasspath);
		// and now pre initialize all necessary imports
		final List<String> snippets = Stream.concat(
			Stream.of("var documentationProfile = \"" + profile.name() + "\";"),
			toJavaSnippets(jShell, STATIC_IMPORT).stream()
		).toList();
		final InvocationResult invocationResult = executeJShellCommands(
			jShell,
			snippets
		);
		if (invocationResult.exception() != null) {
			throw new IllegalStateException(invocationResult.exception());
		}
	}
}
