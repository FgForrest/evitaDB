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

import graphql.com.google.common.collect.Streams;
import io.evitadb.core.Evita;
import io.evitadb.documentation.Environment;
import io.evitadb.documentation.TestContext;
import io.evitadb.documentation.UserDocumentationTest;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.Snippet.Status;
import jdk.jshell.SnippetEvent;
import jdk.jshell.VarSnippet;
import jdk.jshell.execution.LocalExecutionControlProvider;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
	/**
	 * Stored last invocation result.
	 */
	private InvocationResult lastInvocationResult;

	/**
	 * Method clears the tear down snippet and deletes {@link Evita} directory if it was accessed in
	 * test.
	 */
	private static void clearOnTearDown(@Nonnull JShell jShell, @Nonnull SourceToSnippets tearDownSource) {
		final List<Snippet> snippets = tearDownSource.snippets();
		for (int i = snippets.size() - 1; i >= 0; i--) {
			final Snippet tearDownSnippet = snippets.get(i);

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
	}

	public JavaTestContext(@Nonnull Environment profile) {
		this.jShell = JShell.builder()
			// this is faster because JVM is not forked for each test
			.executionEngine(new LocalExecutionControlProvider(), Collections.emptyMap())
			.out(System.out)
			.err(System.err)
			.build();
		// we copy the entire classpath of this test to the JShell instance
		Arrays.stream(System.getProperty("java.class.path").split(":"))
			.forEach(this.jShell::addToClasspath);
		// and now pre initialize all necessary imports
		final List<String> snippets = Stream.concat(
			Stream.of("var documentationProfile = \"" + profile.name() + "\";"),
			toJavaSnippets(this.jShell, STATIC_IMPORT).stream()
		).toList();
		final InvocationResult invocationResult = executeJShellCommandsInternal(snippets);
		if (invocationResult.exception() != null) {
			throw new IllegalStateException(invocationResult.exception());
		}
	}

	/**
	 * Method executes the list of {@link Snippet} in passed {@link JShell} instance a verifies that the execution
	 * finished without an error.
	 *
	 * Method reuses the last JShell instance including its state and rolls back only those snippets which differ
	 * in comparison to a passed set of snippets.
	 */
	@Nonnull
	public InvocationResult executeJShellCommands(@Nonnull List<String> snippets, @Nonnull SideEffect sideEffect) {
		return executeJShellCommands(snippets, sideEffect, null);
	}

	/**
	 * Method executes the list of {@link Snippet} in passed {@link JShell} instance a verifies that the execution
 	 * finished without an error.
	 *
	 * Method reuses the last JShell instance including its state and rolls back only those snippets which differ
	 * in comparison to a passed set of snippets.
	 */
	@Nonnull
	public InvocationResult executeJShellCommands(@Nonnull List<String> snippets, @Nonnull SideEffect sideEffect, @Nullable UnaryOperator<InvocationResult> lambda) {
		int index = 0;
		if (this.lastInvocationResult != null) {
			final int leastSize = Math.min(this.lastInvocationResult.snippets.size(), snippets.size());
			for (; index < leastSize; index++) {
				if (!this.lastInvocationResult.snippets.get(index).source().equals(snippets.get(index))) {
					break;
				}
			}
			cleanJShell(this.lastInvocationResult.snippets().subList(index, this.lastInvocationResult.snippets.size()));
		}

		InvocationResult invocationResult;
		if (index > 0) {
			final InvocationResult intermediateResult = executeJShellCommandsInternal(snippets.subList(index, snippets.size()));
			invocationResult = new InvocationResult(
				Streams.concat(
					this.lastInvocationResult.snippets().subList(0, index).stream(),
					intermediateResult.snippets().stream()
				).toList(),
				intermediateResult.exception()
			);
		} else {
			invocationResult = executeJShellCommandsInternal(snippets);
		}

		if (lambda != null) {
			invocationResult = lambda.apply(invocationResult);
		}

		// if there was exception - clean context entirely and reset last invocation result
		if (invocationResult.exception() != null || sideEffect == SideEffect.WITH_SIDE_EFFECT) {
			cleanJShell(invocationResult.snippets());
			this.lastInvocationResult = null;
		} else {
			this.lastInvocationResult = invocationResult;
		}
		return invocationResult;
	}

	/**
	 * Cleans JShell instance by dropping all snippets and closing all resources that differ in the source code from
	 * the new snippet list.
	 */
	public void cleanJShell(@Nonnull List<SourceToSnippets> sourceToSnippets) {
		for (int i = sourceToSnippets.size() - 1; i >= 0; i--) {
			final SourceToSnippets sourceToSnippet = sourceToSnippets.get(i);
			final List<Snippet> snippets = sourceToSnippet.snippets();
			for (int j = snippets.size() - 1; j >= 0; j--) {
				final Snippet snippet = snippets.get(j);
				// if the snippet declared an AutoCloseable variable, we need to close it
				if (snippet instanceof VarSnippet varSnippet) {
					// there is no way how to get the reference of the variable - so the clean up
					// must be performed by another snippet
					executeJShellCommandsInternal(
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
						.forEach(it -> clearOnTearDown(this.jShell, it));
				}
				// each snippet is "dropped" by the JShell instance (undone)
				this.jShell.drop(snippet);
			}
		}
	}

	/**
	 * Cleans context and closes JShell instance.
	 */
	public void tearDown() {
		if (this.lastInvocationResult != null) {
			cleanJShell(this.lastInvocationResult.snippets());
		}
		this.jShell.close();
	}

	/**
	 * Method executes the list of {@link Snippet} in passed {@link JShell} instance a verifies that the execution
	 * finished without an error.
	 */
	@Nonnull
	private InvocationResult executeJShellCommandsInternal(@Nonnull List<String> snippets) {
		final List<RuntimeException> exceptions = new LinkedList<>();
		final List<SourceToSnippets> executedSources = new ArrayList<>(snippets.size());

		// iterate over snippets and execute them
		for (String snippet : snippets) {
			final List<SnippetEvent> events = this.jShell.eval(snippet);
			final ArrayList<Snippet> executedSnippets = new ArrayList<>(events.size());
			// verify the output events triggered by the execution
			for (SnippetEvent event : events) {
				// if the snippet is not active
				if (!event.status().isActive()) {
					// collect the compilation error and the problematic position and register exception
					exceptions.add(
						new JavaCompilationException(
							this.jShell.diagnostics(event.snippet())
								.map(it ->
									"\n- [" + it.getStartPosition() + "-" + it.getEndPosition() + "] " +
										it.getMessage(Locale.ENGLISH)
								)
								.collect(Collectors.joining()),
							event.snippet().source()
						)
					);
					// it the event contains exception
				} else if (event.exception() != null) {
					// it means, that code was successfully compiled, but threw exception upon evaluation
					exceptions.add(
						new JavaExecutionException(event.exception())
					);
					// add the snippet to the list of executed ones
					if (event.status() == Status.VALID) {
						executedSnippets.add(event.snippet());
					}
				} else {
					// it means, that code was successfully compiled and executed without exception
					executedSnippets.add(event.snippet());
				}
			}
			executedSources.add(
				new SourceToSnippets(
					snippet,
					executedSnippets
				)
			);
			// if the exception is not null, fail fast and report the exception
			if (!exceptions.isEmpty()) {
				break;
			}
		}

		// return all snippets that has been executed and report exception if occurred
		return new InvocationResult(
			executedSources,
			exceptions.isEmpty() ? null : exceptions.get(0)
		);
	}

	/**
	 * Record contains result of the Java code execution.
	 */
	public record InvocationResult(
		@Nonnull List<SourceToSnippets> snippets,
		@Nullable Exception exception
	) {
	}

	/**
	 * This record contains all snippets for the given source code
	 */
	public record SourceToSnippets(
		@Nonnull String source,
		@Nonnull List<Snippet> snippets
	) {
	}

	/**
	 * Specifies whether the test has side effects and therefore must be completely rolled back after execution.
	 */
	public enum SideEffect {

		WITHOUT_SIDE_EFFECT,
		WITH_SIDE_EFFECT;

	}

}
