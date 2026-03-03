/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.documentation;

import graphql.com.google.common.collect.Streams;
import io.evitadb.documentation.csharp.CsharpExecutable;
import io.evitadb.documentation.csharp.CsharpTestContextFactory;
import io.evitadb.documentation.evitaql.EvitaQLExecutable;
import io.evitadb.documentation.evitaql.EvitaTestContextFactory;
import io.evitadb.documentation.graphql.GraphQLExecutable;
import io.evitadb.documentation.graphql.GraphQLTestContextFactory;
import io.evitadb.documentation.java.JavaExecutable;
import io.evitadb.documentation.java.JavaTestContext.SideEffect;
import io.evitadb.documentation.java.JavaTestContextFactory;
import io.evitadb.documentation.java.JavaWrappingExecutable;
import io.evitadb.documentation.rest.RestExecutable;
import io.evitadb.documentation.rest.RestTestContextFactory;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import jdk.jshell.JShell;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * This test class generates dynamic tests that compile and execute all source code examples found in user
 * documentation MarkDown files. The test searches for all occurrences of:
 *
 * 1. ``` java ``` block
 * 2. `<SourceCodeTabs>` block
 * 3. `<SourceAlternativeTabs>` block
 *
 * Tests are organized in a three-level hierarchy:
 *
 * - **File-level** containers (one per MarkDown file)
 * - **Language-level** containers within each file (one per language: java, evitaql, graphql, rest, cs)
 * - **Individual** test cases within each language (one per code snippet)
 *
 * Each language gets its own {@link JShell}/client context, enabling parallel execution across languages
 * while tests within the same language run sequentially (they share state). Files containing `local` examples
 * acquire an exclusive lock to prevent port conflicts.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class UserDocumentationTest implements EvitaTestSupport {
	/**
	 * Pattern for searching for ``` java ``` blocks.
	 */
	private static final Pattern SOURCE_CODE_PATTERN = Pattern.compile(
		"```\\s*(\\S+)?\\s*\n(.+?)\\s*```",
		Pattern.DOTALL | Pattern.MULTILINE
	);

	/**
	 * Pattern for searching for <SourceCodeTabs> blocks.
	 */
	private static final Pattern SOURCE_CODE_TABS_PATTERN = Pattern.compile(
		"<SourceCodeTabs(.*?)?>\\s*\\[.*?]\\((.*?)\\)\\s*</SourceCodeTabs>",
		Pattern.DOTALL | Pattern.MULTILINE
	);
	/**
	 * <SourceAlternativeTabs variants="interface|record|class">
	 * Pattern for searching for <SourceAlternativeTabs> blocks.
	 */
	private static final Pattern SOURCE_ALTERNATIVE_TABS_PATTERN = Pattern.compile(
		"<SourceAlternativeTabs(.*?)?>\\s*\\[.*?]\\((.*?)\\)\\s*</SourceAlternativeTabs>",
		Pattern.DOTALL | Pattern.MULTILINE
	);
	/**
	 * Pattern for searching for <MDInclude> blocks.
	 */
	private static final Pattern MD_INCLUDE_PATTERN = Pattern.compile(
		"<MDInclude\\s*(sourceVariable=\"(.*?)\")?>\\s*\\[.*?]\\((.*?)\\)\\s*</MDInclude>",
		Pattern.DOTALL | Pattern.MULTILINE
	);
	/**
	 * Field containing the relative directory path to the user documentation.
	 */
	private static final String DOCS_ROOT = "documentation/user/en/";
	/**
	 * Field contains list of all languages that are not tested by this class.
	 */
	private static final Set<String> NOT_TESTED_LANGUAGES = new HashSet<>();

	static {
		NOT_TESTED_LANGUAGES.add("evitaql-syntax");
		NOT_TESTED_LANGUAGES.add("md");
		NOT_TESTED_LANGUAGES.add("bash");
		NOT_TESTED_LANGUAGES.add("Maven");
		NOT_TESTED_LANGUAGES.add("Gradle");
		NOT_TESTED_LANGUAGES.add("shell");
		NOT_TESTED_LANGUAGES.add("json");
		NOT_TESTED_LANGUAGES.add("yaml");
		NOT_TESTED_LANGUAGES.add("plain");
		NOT_TESTED_LANGUAGES.add("protobuf");
	}

	/**
	 * Lock ensuring local-example files (which bind fixed ports on localhost) never overlap
	 * with each other. Only local files acquire this lock; demo-server files skip it entirely
	 * since they target an external server and can safely run in parallel with everything.
	 * Fair ordering prevents starvation when local files queue behind each other.
	 */
	private static final Lock LOCAL_LOCK = new ReentrantLock(true);

	/**
	 * Reads UTF-8 string executable from the file with changed extension.
	 */
	@Nonnull
	public static Optional<String> readFile(@Nonnull Path path, @Nonnull String extension) {
		final Path readFileName = resolveSiblingWithDifferentExtension(path, extension);
		return readFile(readFileName);
	}

	/**
	 * Reads UTF-8 string executable from the file.
	 */
	@Nonnull
	public static Optional<String> readFile(@Nonnull Path readFileName) {
		try {
			if (readFileName.toFile().exists()) {
				return Optional.of(Files.readString(readFileName, StandardCharsets.UTF_8));
			} else {
				return Optional.empty();
			}
		} catch (IOException e) {
			Assertions.fail(e);
			// doesn't happen
			return Optional.empty();
		}
	}

	/**
	 * Reads UTF-8 string executable from the file.
	 */
	@Nonnull
	public static String readFileOrThrowException(@Nonnull Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Assertions.fail(e);
			// doesn't happen
			return "";
		}
	}

	/**
	 * Resolves sibling file with different filename extension.
	 */
	@Nonnull
	public static Path resolveSiblingWithDifferentExtension(@Nonnull Path path, @Nonnull String extension) {
		final String sourceFileName = path.getFileName().toString();
		return path.resolveSibling(
			sourceFileName.substring(0, sourceFileName.lastIndexOf('.')) + "." + extension
		).normalize();
	}

	/**
	 * Returns true if the file has extension in its file name.
	 */
	private static boolean hasFileNameExtension(@Nonnull Path referencedFile) {
		return ofNullable(referencedFile.getFileName().toString())
			.map(f -> f.contains("."))
			.orElse(false);
	}

	/**
	 * Extract the extension from the file name.
	 */
	@Nonnull
	private static String getFileNameExtension(@Nonnull Path referencedFile) {
		return ofNullable(referencedFile.getFileName().toString())
			.filter(f -> f.contains("."))
			.map(f -> f.substring(f.lastIndexOf('.') + 1))
			.orElseThrow(() -> new IllegalArgumentException("File name must contain `.`!"));
	}

	/**
	 * Removes the extension from the file name and returns the altered Path to the file without the extension.
	 */
	@Nonnull
	private static Optional<Path> stripFileNameOfExtension(@Nonnull Path referencedFile) {
		return ofNullable(referencedFile.getFileName().toString())
			.filter(f -> f.contains("."))
			.map(f -> referencedFile.resolveSibling(f.substring(0, f.lastIndexOf('.'))));
	}

	/**
	 * Method converts the source format to the Java executable that can be run in {@link JShell}.
	 *
	 * @param sourceFormat  the file format of the source code
	 * @param sourceContent the executable of the source code
	 * @return the source code translated from source code to Java executable code
	 */
	@Nonnull
	private static Executable convertToRunnable(
		@Nonnull Environment profile,
		@Nonnull String sourceFormat,
		@Nonnull String sourceContent,
		@Nonnull Path rootPath,
		@Nullable Path resource,
		@Nullable Path[] setupResources,
		@Nullable Path[] requiredResources,
		@Nonnull SideEffect sideEffect,
		@Nonnull TestContextProvider contextAccessor,
		@Nonnull Map<Path, CodeSnippet> codeSnippetIndex,
		@Nullable List<OutputSnippet> outputSnippet,
		@Nonnull CreateSnippets... createSnippets
	) {
		switch (sourceFormat) {
			case "java" -> {
				return new JavaExecutable(
					contextAccessor.get(profile, JavaTestContextFactory.class),
					sourceContent,
					Streams.concat(
							ofNullable(setupResources).stream().flatMap(Arrays::stream),
							ofNullable(requiredResources).stream().flatMap(Arrays::stream).filter(x -> x.toString().endsWith(".java"))
					).toArray(Path[]::new),
					sideEffect,
					codeSnippetIndex
				);
			}
			case "evitaql" -> {
				return new EvitaQLExecutable(
					contextAccessor.get(profile, EvitaTestContextFactory.class),
					sourceContent,
					rootPath,
					resource,
					outputSnippet,
					createSnippets
				);
			}
			case "graphql" -> {
				final GraphQLExecutable graphQLExecutable = new GraphQLExecutable(
					contextAccessor.get(profile, GraphQLTestContextFactory.class),
					sourceContent,
					rootPath,
					resource,
					outputSnippet,
					createSnippets
				);
				return ArrayUtils.isEmpty(setupResources) ?
					graphQLExecutable :
					new JavaWrappingExecutable(
						contextAccessor.get(profile, JavaTestContextFactory.class),
						graphQLExecutable,
						setupResources,
						sideEffect,
						codeSnippetIndex
					);
			}
			case "rest" -> {
				final RestExecutable restExecutable = new RestExecutable(
					contextAccessor.get(profile, RestTestContextFactory.class),
					sourceContent,
					rootPath,
					resource,
					outputSnippet,
					createSnippets
				);
				return ArrayUtils.isEmpty(setupResources) ?
					restExecutable :
					new JavaWrappingExecutable(
						contextAccessor.get(profile, JavaTestContextFactory.class),
						restExecutable,
						setupResources,
						sideEffect,
						codeSnippetIndex
					);
			}
			case "cs" -> {
				final CsharpExecutable csharpExecutable = new CsharpExecutable(
					contextAccessor.get(profile, CsharpTestContextFactory.class),
					sourceContent,
					rootPath,
					resource,
					ofNullable(requiredResources)
						.map(it -> Arrays.stream(it).filter(x -> x.toString().endsWith(".cs")).toArray(Path[]::new))
						.orElse(null),
					codeSnippetIndex,
					outputSnippet
				);
				return ArrayUtils.isEmpty(setupResources) ?
					csharpExecutable :
					new JavaWrappingExecutable(
						contextAccessor.get(profile, JavaTestContextFactory.class),
						csharpExecutable,
						setupResources,
						sideEffect,
						codeSnippetIndex
					);
			}
			default -> {
				throw new UnsupportedOperationException("Unsupported file format: " + sourceFormat);
			}
		}
	}

	/**
	 * Returns array of files with same name and different extension from the same directory.
	 */
	@Nonnull
	private static List<Path> findRelatedFiles(@Nonnull Path theFile, @Nonnull Set<Path> alreadyUsedPaths, Map<Path, CodeSnippet> codeSnippetIndex) {
		try (final Stream<Path> siblings = Files.list(theFile.getParent())) {
			final String theFileName = theFile.getFileName().toString();
			final String theFileExtension = getFileNameExtension(theFile);
			return siblings.filter(it -> {
					final String fileName = it.getFileName().toString();
					final String fileNameExtension = getFileNameExtension(it);
					return !NOT_TESTED_LANGUAGES.contains(fileNameExtension) &&
						!theFileExtension.equals(fileNameExtension) &&
						!alreadyUsedPaths.contains(it) &&
						fileName.substring(0, fileName.length() - fileNameExtension.length())
							.equals(theFileName.substring(0, theFileName.length() - theFileExtension.length()));
				})
				.map(Path::normalize)
				.filter(it -> !alreadyUsedPaths.contains(it))
				.peek(alreadyUsedPaths::add)
				.toList();
		} catch (IOException e) {
			Assertions.fail(
				e.getMessage()
			);
			return null;
		}
	}

	/**
	 * Returns array of files with same name and different extension from the same directory.
	 */
	@Nonnull
	private static List<Path> findVariants(@Nonnull Path theFile, @Nonnull Set<Path> alreadyUsedPaths, @Nonnull String[] variants) {
		Integer indexOfMainVariant = null;
		final String theFileName = theFile.getFileName().toString();
		final String theFileExtension = getFileNameExtension(theFile);
		if (NOT_TESTED_LANGUAGES.contains(theFileExtension)) {
			return Collections.emptyList();
		}
		for (int i = 0; i < variants.length; i++) {
			if (theFileName.contains(variants[i])) {
				indexOfMainVariant = i;
				break;
			}
		}
		if (indexOfMainVariant == null) {
			throw new IllegalArgumentException("The file name `" + theFileName + "` must contain one of the variants: " + Arrays.toString(variants) + "!");
		}

		final List<Path> variantsFiles = new LinkedList<>();
		for (int i = 0; i < variants.length; i++) {
			if (i != indexOfMainVariant) {
				final String variant = variants[i];
				final Path variantFile = theFile.getParent().resolve(theFileName.replace(variants[indexOfMainVariant], variant)).normalize();
				if (!alreadyUsedPaths.contains(variantFile)) {
					alreadyUsedPaths.add(variantFile);
					variantsFiles.add(variantFile);
				}
			}
		}

		return variantsFiles;
	}

	/**
	 * Creates path relative to the root directory.
	 */
	@Nonnull
	private static Path createPathRelativeToRootDirectory(@Nonnull Path rootDirectory, @Nonnull String path) {
		return rootDirectory.resolve(!path.isEmpty() && path.charAt(0) == '/' ? path.substring(1) : path).normalize();
	}

	/**
	 * Creates a {@link CodeSnippet} for the given file, adds it to the appropriate language bucket,
	 * and registers it in the code snippet index for cross-snippet dependency resolution.
	 *
	 * @param referencedFile           path to the source file
	 * @param referencedFileExtension  language extension of the file
	 * @param environment              execution environment (demo server or localhost)
	 * @param setupScripts             setup scripts to run before the example
	 * @param requiredScripts          required scripts for the example
	 * @param sideEffect               whether the example has side effects
	 * @param outputSnippets           expected output snippets
	 * @param rootDirectory            root directory of the project
	 * @param contextProvider          shared context provider for the file
	 * @param snippetsByLanguage       per-language snippet lists to populate
	 * @param codeSnippetIndex         shared snippet index for dependency resolution
	 * @param createSnippets           snippet generation options
	 */
	private static void addPrimarySnippet(
		@Nonnull Path referencedFile,
		@Nonnull String referencedFileExtension,
		@Nonnull Environment environment,
		@Nullable Path[] setupScripts,
		@Nullable Path[] requiredScripts,
		@Nonnull SideEffect sideEffect,
		@Nonnull List<OutputSnippet> outputSnippets,
		@Nonnull Path rootDirectory,
		@Nonnull TestContextProvider contextProvider,
		@Nonnull Map<String, List<CodeSnippet>> snippetsByLanguage,
		@Nonnull Map<Path, CodeSnippet> codeSnippetIndex,
		@Nonnull CreateSnippets... createSnippets
	) {
		final CodeSnippet snippet = new CodeSnippet(
			"Example `" + referencedFile.getFileName() + "`",
			referencedFileExtension,
			referencedFile,
			convertToRunnable(
				environment,
				referencedFileExtension,
				readFileOrThrowException(referencedFile),
				rootDirectory,
				referencedFile,
				setupScripts,
				requiredScripts,
				sideEffect,
				contextProvider,
				codeSnippetIndex,
				outputSnippets,
				createSnippets
			)
		);
		snippetsByLanguage.get(referencedFileExtension).add(snippet);
		codeSnippetIndex.put(snippet.path(), snippet);
	}

	/**
	 * Creates a {@link CodeSnippet} for a related or variant file and adds it
	 * to the appropriate language bucket.
	 *
	 * @param relatedFile              path to the related source file
	 * @param environment              execution environment
	 * @param setupScripts             setup scripts to run before the example
	 * @param requiredScripts          required scripts for the example
	 * @param sideEffect               whether the example has side effects
	 * @param rootDirectory            root directory of the project
	 * @param contextProvider          shared context provider for the file
	 * @param snippetsByLanguage       per-language snippet lists to populate
	 * @param filteredExtensions       set of language extensions to include
	 * @param outputSnippetIndex       index mapping files to their output snippets
	 * @param codeSnippetIndex         shared snippet index for dependency resolution
	 * @param createSnippets           snippet generation options
	 */
	private static void addRelatedSnippet(
		@Nonnull Path relatedFile,
		@Nonnull Environment environment,
		@Nullable Path[] setupScripts,
		@Nullable Path[] requiredScripts,
		@Nonnull SideEffect sideEffect,
		@Nonnull Path rootDirectory,
		@Nonnull TestContextProvider contextProvider,
		@Nonnull Map<String, List<CodeSnippet>> snippetsByLanguage,
		@Nonnull Set<String> filteredExtensions,
		@Nonnull Map<Path, List<OutputSnippet>> outputSnippetIndex,
		@Nonnull Map<Path, CodeSnippet> codeSnippetIndex,
		@Nonnull CreateSnippets... createSnippets
	) {
		final String relatedExt = getFileNameExtension(relatedFile);
		if (!filteredExtensions.contains(relatedExt)) {
			return;
		}
		// C# snippets use the .evitaql counterpart for output comparison
		final Path outputKey = "cs".equals(relatedExt)
			? Path.of(relatedFile.toString().replace(".cs", ".evitaql"))
			: relatedFile;
		final List<OutputSnippet> outputSnippets =
			ofNullable(outputSnippetIndex.get(outputKey))
				.orElse(Collections.emptyList());

		snippetsByLanguage.get(relatedExt).add(
			new CodeSnippet(
				"Example `" + relatedFile.getFileName() + "`",
				relatedExt,
				relatedFile,
				convertToRunnable(
					environment,
					relatedExt,
					readFileOrThrowException(relatedFile),
					rootDirectory,
					relatedFile,
					setupScripts,
					requiredScripts,
					sideEffect,
					contextProvider,
					codeSnippetIndex,
					outputSnippets,
					createSnippets
				)
			)
		);
	}

	/**
	 * This test scans all MarkDown files in the user documentation directory and generates {@link DynamicTest}
	 * instances for each source code block found. Tests are organized in a three-level hierarchy:
	 *
	 * - **File-level containers** run in parallel (non-local) or exclusively (local)
	 * - **Language-level containers** within each file run in parallel (for non-local files)
	 * - **Individual tests** within each language run sequentially (they share JShell/client state)
	 *
	 * Files containing at least one `local` example acquire an exclusive write lock to prevent
	 * port conflicts. Non-local files acquire a shared read lock allowing concurrent execution.
	 */
	@DisplayName("User documentation")
	@Tag(DOCUMENTATION_TEST)
	@TestFactory
	Stream<DynamicNode> testDocumentation() throws IOException {
		try (final Stream<Path> walker = Files.walk(getRootDirectory().resolve(DOCS_ROOT))) {
			final List<DynamicNode> nodes = walker
				.filter(path -> path.toString().endsWith(".md"))
				.map(it -> {
					final FileTestResult result = this.createTests(
						Environment.DEMO_SERVER,
						it,
						new ExampleFilter[]{
							// ExampleFilter.CSHARP,
							ExampleFilter.JAVA,
							ExampleFilter.REST,
							ExampleFilter.GRAPHQL,
							ExampleFilter.EVITAQL
						}
					);
					if (result.languageContainers().isEmpty()) {
						return null;
					} else {
						return (DynamicNode) wrapFileContainer(it, result);
					}
				})
				.filter(Objects::nonNull)
				.toList();
			return nodes.stream();
		}
	}

	/**
	 * Wraps language containers for a single MarkDown file into a file-level {@link DynamicContainer}
	 * with appropriate execution mode and lock acquisition/release tests.
	 *
	 * Local files acquire an exclusive write lock (preventing overlap with any other file)
	 * and run their language containers sequentially. Non-local files acquire a shared read lock
	 * and run their language containers in parallel.
	 *
	 * @param filePath path to the MarkDown file
	 * @param result   the parsed test result containing language containers and local flag
	 * @return a file-level dynamic container with proper parallelization settings
	 */
	@Nonnull
	private static DynamicNode wrapFileContainer(
		@Nonnull Path filePath,
		@Nonnull FileTestResult result
	) {
		final boolean local = result.hasLocalExamples();
		final String displaySuffix = local ? " (local)" : "";
		// local files run all languages sequentially; non-local allow per-language parallelism
		final ExecutionMode languageMode = local
			? ExecutionMode.SAME_THREAD
			: ExecutionMode.CONCURRENT;
		final String fileName = filePath.getFileName().toString();

		// inner container holds the language containers with appropriate concurrency
		final DynamicNode languagesContainer = DynamicContainer.dynamicContainer(
			config -> {
				config.displayName("Languages");
				config.childExecutionMode(languageMode);
				config.children(result.languageContainers().stream());
			}
		);

		// outer container ensures sequential: [lock] → init → languages → teardown → [unlock]
		return DynamicContainer.dynamicContainer(config -> {
			config.displayName(
				"File: `" + filePath.getFileName() + "`" + displaySuffix
			);
			config.testSourceUri(filePath.toUri());
			// file itself can be scheduled concurrently with other files
			config.executionMode(ExecutionMode.CONCURRENT);
			// but its children must be sequential (init before snippets, teardown after)
			config.childExecutionMode(ExecutionMode.SAME_THREAD);
			if (local) {
				// local files acquire the lock to prevent port conflicts with other local files;
				// demo-server files skip locking entirely - they can run in parallel with everything
				config.children(Stream.of(
					Stream.of(dynamicTest("Acquire local lock", () -> {
						System.out.println("[DOC-TEST] " + Thread.currentThread().getName()
							+ " | Acquiring local lock for: " + fileName);
						LOCAL_LOCK.lock();
						System.out.println("[DOC-TEST] " + Thread.currentThread().getName()
							+ " | Acquired local lock for: " + fileName);
					})),
					result.initTests().stream(),
					Stream.of(languagesContainer),
					result.tearDownTests().stream(),
					Stream.of(dynamicTest("Release local lock", () -> {
						System.out.println("[DOC-TEST] " + Thread.currentThread().getName()
							+ " | Releasing local lock for: " + fileName);
						LOCAL_LOCK.unlock();
					}))
				).flatMap(Function.identity()));
			} else {
				// demo-server files: no lock needed, just init → languages → teardown
				config.children(Stream.of(
					Stream.of(dynamicTest("Start file", () ->
						System.out.println("[DOC-TEST] " + Thread.currentThread().getName()
							+ " | Starting: " + fileName)
					)),
					result.initTests().stream(),
					Stream.of(languagesContainer),
					result.tearDownTests().stream()
				).flatMap(Function.identity()));
			}
		});
	}

	/**
	 * The test is disabled and should be used only for "debugging" snippets in certain documentation file.
	 */
	@DisplayName("Debug user documentation")
	@TestFactory
	@Tag(DOCUMENTATION_TEST)
	@Disabled
	Stream<DynamicNode> testSingleFileDocumentation() {
		final FileTestResult result = this.createTests(
			Environment.DEMO_SERVER,
			getRootDirectory().resolve("documentation/user/en/use/connectors/grpc.md"),
			new ExampleFilter[]{
				/*ExampleFilter.CSHARP,*/
				ExampleFilter.JAVA,
				ExampleFilter.REST,
				ExampleFilter.GRAPHQL,
				ExampleFilter.EVITAQL
			}
		);
		return Stream.of(
			result.initTests().stream(),
			result.languageContainers().stream(),
			result.tearDownTests().stream()
		).flatMap(Function.identity());
	}

	/**
	 * The test is disabled and should be used only for MarkDown snippets from EvitaQL examples in certain documentation
	 * file. The MarkDown snippets are generated according to an attribute list requested by EvitaQL query and taken
	 * from the last variable found.
	 */
	@DisplayName("Create snippets in other languages from EvitaQL examples")
	@TestFactory
	@Tag(DOCUMENTATION_TEST)
	@Disabled
	Stream<DynamicNode> testSingleFileDocumentationAndCreateOtherLanguageSnippets() {
		final FileTestResult result = this.createTests(
			Environment.DEMO_SERVER,
			getRootDirectory().resolve("documentation/user/en/query/ordering/random.md"),
			new ExampleFilter[]{
				/*ExampleFilter.CSHARP,*/
				ExampleFilter.JAVA,
				ExampleFilter.REST,
				ExampleFilter.GRAPHQL,
				ExampleFilter.EVITAQL
			},
			CreateSnippets.MARKDOWN, CreateSnippets.JAVA, CreateSnippets.GRAPHQL, CreateSnippets.REST
		);
		return Stream.of(
			result.initTests().stream(),
			result.languageContainers().stream(),
			result.tearDownTests().stream()
		).flatMap(Function.identity());
	}

	/**
	 * Method creates per-language {@link DynamicContainer} instances for all code blocks in the file of given
	 * {@link Path}. A single shared {@link TestContextProvider} is used per file (since cross-language dependencies
	 * exist - e.g. GraphQL/REST with setup scripts need Java JShell context). Init/teardown tests from the shared
	 * provider run sequentially before/after the parallel language phase.
	 *
	 * Returns a {@link FileTestResult} containing the language containers and whether the file has local examples.
	 */
	@Nonnull
	private FileTestResult createTests(
		@Nonnull Environment profile,
		@Nonnull Path path,
		@Nonnull ExampleFilter[] exampleFilters,
		@Nonnull CreateSnippets... createSnippets
	) {
		final Path rootDirectory = getRootDirectory();
		// shared index for resolving cross-snippet dependencies (populated single-threaded, read concurrently)
		final Map<Path, CodeSnippet> codeSnippetIndex = new HashMap<>();
		final Set<String> filteredExtensions = Arrays.stream(exampleFilters)
			.map(ExampleFilter::getExtension)
			.collect(Collectors.toSet());

		final String fileContent = readFileOrThrowException(path);
		final AtomicInteger index = new AtomicInteger();
		final Set<Path> alreadyUsedPaths = new HashSet<>();
		boolean hasLocalExamples = false;

		// single shared context provider per file - cross-language dependencies exist
		// (e.g. GraphQL/REST with setup scripts use JavaWrappingExecutable which needs Java context)
		final TestContextProvider contextProvider = new TestContextProvider();

		// per-language snippet groups (preserving insertion order for deterministic test output)
		final Map<String, List<CodeSnippet>> snippetsByLanguage = new LinkedHashMap<>(8);
		for (ExampleFilter filter : exampleFilters) {
			snippetsByLanguage.put(filter.getExtension(), new LinkedList<>());
		}

		// 1. Parse inline ``` code ``` blocks
		final Matcher sourceCodeMatcher = SOURCE_CODE_PATTERN.matcher(fileContent);
		while (sourceCodeMatcher.find()) {
			final String format = sourceCodeMatcher.groupCount() > 2 ? sourceCodeMatcher.group(1) : "plain";
			final String content = sourceCodeMatcher.groupCount() > 2 ? sourceCodeMatcher.group(2) : sourceCodeMatcher.group(1);
			if (!(format.isBlank() || NOT_TESTED_LANGUAGES.contains(format)) && filteredExtensions.contains(format)) {
				snippetsByLanguage.get(format).add(
					new CodeSnippet(
						"Example #" + index.incrementAndGet(),
						format,
						null,
						convertToRunnable(
							profile,
							format,
							content,
							rootDirectory,
							null,
							null,
							null,
							SideEffect.WITH_SIDE_EFFECT,
							contextProvider,
							codeSnippetIndex,
							Collections.emptyList()
						)
					)
				);
			}
		}

		// 2. Build output snippet index from MDInclude blocks
		final Map<Path, List<OutputSnippet>> outputSnippetIndex = new HashMap<>();
		final Matcher mdIncludeMatcher = MD_INCLUDE_PATTERN.matcher(fileContent);
		while (mdIncludeMatcher.find()) {
			final String sourceVariable = mdIncludeMatcher.group(2);
			final Path outputSnippetFile = createPathRelativeToRootDirectory(rootDirectory, mdIncludeMatcher.group(3));
			final Optional<Path> outputSnippetFormatBase = stripFileNameOfExtension(outputSnippetFile);
			final Optional<Path> sourceExampleFile = outputSnippetFormatBase.flatMap(UserDocumentationTest::stripFileNameOfExtension);
			final boolean isSourceExampleFileUsable = sourceExampleFile.map(UserDocumentationTest::hasFileNameExtension).orElse(false);
			outputSnippetFormatBase.ifPresent(
				base -> outputSnippetIndex
					.computeIfAbsent(
						isSourceExampleFileUsable ? sourceExampleFile.get() : base, thePath -> new LinkedList<>()
					)
					.add(
						new OutputSnippet(
							isSourceExampleFileUsable ? getFileNameExtension(base) : "md",
							outputSnippetFile, sourceVariable
						)
					));
		}

		// 3. Parse <SourceCodeTabs> blocks - distribute primary and related snippets to their language buckets
		final Matcher sourceCodeTabsMatcher = SOURCE_CODE_TABS_PATTERN.matcher(fileContent);
		while (sourceCodeTabsMatcher.find()) {
			final Path referencedFile = createPathRelativeToRootDirectory(rootDirectory, sourceCodeTabsMatcher.group(2));
			final Attributes attributes = new Attributes(sourceCodeTabsMatcher.group(1));
			final String referencedFileExtension = getFileNameExtension(referencedFile);
			if (attributes.isIgnoreTest()) {
				continue;
			}
			if (attributes.isLocal()) {
				hasLocalExamples = true;
			}
			if (profile == Environment.LOCALHOST && attributes.isLocal()) {
				// skip local tests when profile is localhost - it starts a local evitaDB instance
				// which will fail on already opened ports
				continue;
			}
			final Environment environment = attributes.isLocal()
				? Environment.LOCALHOST : profile;
			final SideEffect sideEffect = attributes.isLocal()
				? SideEffect.WITH_SIDE_EFFECT
				: SideEffect.WITHOUT_SIDE_EFFECT;

			if (!NOT_TESTED_LANGUAGES.contains(referencedFileExtension)) {
				alreadyUsedPaths.add(referencedFile);
				final Path[] setupScripts = attributes.getSetupScripts(rootDirectory);
				final Path[] requiredScripts = attributes.getRequiredScripts(rootDirectory);
				final List<OutputSnippet> outputSnippets =
					ofNullable(outputSnippetIndex.get(referencedFile))
						.orElse(Collections.emptyList());

				if (filteredExtensions.contains(referencedFileExtension)) {
					addPrimarySnippet(
						referencedFile, referencedFileExtension,
						environment, setupScripts, requiredScripts,
						sideEffect, outputSnippets, rootDirectory,
						contextProvider, snippetsByLanguage,
						codeSnippetIndex, createSnippets
					);
				}

				for (Path relatedFile : findRelatedFiles(referencedFile, alreadyUsedPaths, codeSnippetIndex)) {
					addRelatedSnippet(
						relatedFile, environment,
						setupScripts, requiredScripts, sideEffect,
						rootDirectory, contextProvider,
						snippetsByLanguage, filteredExtensions,
						outputSnippetIndex, codeSnippetIndex,
						createSnippets
					);
				}
			}
		}

		// 4. Parse <SourceAlternativeTabs> blocks - same distribution pattern
		final Matcher sourceAlternativeTabsMatcher = SOURCE_ALTERNATIVE_TABS_PATTERN.matcher(fileContent);
		while (sourceAlternativeTabsMatcher.find()) {
			final Path referencedFile = createPathRelativeToRootDirectory(rootDirectory, sourceAlternativeTabsMatcher.group(2));
			final String referencedFileExtension = getFileNameExtension(referencedFile);
			final Attributes attributes = new Attributes(sourceAlternativeTabsMatcher.group(1));
			final Path[] setupScripts = attributes.getSetupScripts(rootDirectory);
			final Path[] requiredScripts = attributes.getRequiredScripts(rootDirectory);
			final SideEffect sideEffect = attributes.isLocal()
				? SideEffect.WITH_SIDE_EFFECT
				: SideEffect.WITHOUT_SIDE_EFFECT;
			final String[] variants = attributes.getVariants();
			if (attributes.isLocal()) {
				hasLocalExamples = true;
			}
			if (!NOT_TESTED_LANGUAGES.contains(referencedFileExtension)) {
				alreadyUsedPaths.add(referencedFile);
				final List<OutputSnippet> outputSnippets =
					ofNullable(outputSnippetIndex.get(referencedFile))
						.orElse(Collections.emptyList());

				if (filteredExtensions.contains(referencedFileExtension)) {
					addPrimarySnippet(
						referencedFile, referencedFileExtension,
						profile, setupScripts, requiredScripts,
						sideEffect, outputSnippets, rootDirectory,
						contextProvider, snippetsByLanguage,
						codeSnippetIndex, createSnippets
					);
				}

				for (Path relatedFile : findVariants(referencedFile, alreadyUsedPaths, variants)) {
					addRelatedSnippet(
						relatedFile, profile,
						setupScripts, requiredScripts, sideEffect,
						rootDirectory, contextProvider,
						snippetsByLanguage, filteredExtensions,
						outputSnippetIndex, codeSnippetIndex,
						createSnippets
					);
				}
			}
		}

		// 5. Build per-language DynamicContainers (snippet tests only - init/teardown are separate)
		final boolean hasAnySnippets = snippetsByLanguage.values().stream().anyMatch(list -> !list.isEmpty());
		if (!hasAnySnippets) {
			return new FileTestResult(
				Collections.emptyList(), Collections.emptyList(),
				Collections.emptyList(), hasLocalExamples
			);
		}

		final String fileName = path.getFileName().toString();
		final List<DynamicNode> languageContainers = new ArrayList<>(snippetsByLanguage.size());
		for (Map.Entry<String, List<CodeSnippet>> entry : snippetsByLanguage.entrySet()) {
			final String language = entry.getKey();
			final List<CodeSnippet> snippets = entry.getValue();
			if (snippets.isEmpty()) {
				continue;
			}
			final int snippetCount = snippets.size();
			languageContainers.add(DynamicContainer.dynamicContainer(config -> {
				config.displayName("Language: " + language);
				// tests within the same language run sequentially - they share context state
				config.childExecutionMode(ExecutionMode.SAME_THREAD);
				config.children(Stream.of(
					// log language start
					Stream.of(dynamicTest("Start " + language, () ->
						System.out.println("[DOC-TEST] " + Thread.currentThread().getName()
							+ " | Starting " + language + " (" + snippetCount + " snippets) in: " + fileName)
					)),
					// execute code snippet tests
					snippets.stream()
						.map(
							codeSnippet -> dynamicTest(
								codeSnippet.name(),
								ofNullable(codeSnippet.path()).map(Path::toUri).orElse(null),
								() -> codeSnippet.executableLambda().execute()
							)
						),
					// log language finish
					Stream.of(dynamicTest("Finish " + language, () ->
						System.out.println("[DOC-TEST] " + Thread.currentThread().getName()
							+ " | Finished " + language + " in: " + fileName)
					))
				).flatMap(Function.identity()));
			}));
		}

		return new FileTestResult(
			contextProvider.getInitTests(), languageContainers,
			contextProvider.getTearDownTests(), hasLocalExamples
		);
	}

	/**
	 * Enum that covers all supported example types that can be run.
	 */
	@RequiredArgsConstructor
	public enum ExampleFilter {

		EVITAQL("evitaql"), JAVA("java"), GRAPHQL("graphql"), REST("rest"), CSHARP("cs");

		@Getter private final String extension;

	}

	/**
	 * Enum that covers all supported generation options.
	 */
	public enum CreateSnippets {

		JAVA, MARKDOWN, GRAPHQL, REST, CSHARP

	}

	/**
	 * Result of parsing a single MarkDown file, containing shared init/teardown tests,
	 * per-language test containers, and a flag indicating whether the file contains local examples.
	 *
	 * @param initTests          shared context initialization tests (JShell, clients) - must run before snippets
	 * @param languageContainers per-language {@link DynamicContainer}s, each with sequential snippet tests
	 * @param tearDownTests      shared context teardown tests - must run after all snippets
	 * @param hasLocalExamples   true if the file contains at least one example with the `local` attribute
	 */
	private record FileTestResult(
		@Nonnull List<DynamicTest> initTests,
		@Nonnull List<DynamicNode> languageContainers,
		@Nonnull List<DynamicTest> tearDownTests,
		boolean hasLocalExamples
	) {
	}

	/**
	 * Record represents a code block occurrence found in the MarkDown document.
	 *
	 * @param name             title of the code snippet allowing its identification in the document
	 * @param format           source format (language) of the example
	 * @param path             path to the external file containing the code snippet
	 * @param executableLambda the Java code to execute to verify the sample
	 */
	public record CodeSnippet(
		@Nonnull String name,
		@Nonnull String format,
		@Nullable Path path,
		@Nonnull Executable executableLambda
	) {
	}

	/**
	 * Record represents a MarkDown include referencing an external file that conforms with the expected output of
	 * the {@link CodeSnippet}.
	 *
	 * @param forFormat      format (language) with which the output snippet is meant
	 * @param path           path to the external file containing the output snippet
	 * @param sourceVariable text from `sourceVariable` attribute of the MDInclude
	 */
	public record OutputSnippet(
		@Nonnull String forFormat,
		@Nonnull Path path,
		@Nullable String sourceVariable
	) {

	}

	/**
	 * The class provides access and memory for existing {@link TestContext} instances, along with the
	 * {@link DynamicTest} instances required to initialize them and tear them down (lazily).
	 */
	private static class TestContextProvider {
		@Getter
		private final List<DynamicTest> initTests = new LinkedList<>();
		@Getter
		private final List<DynamicTest> tearDownTests = new LinkedList<>();
		@SuppressWarnings("rawtypes")
		private final Map<ContextKey, Supplier> contexts = new HashMap<>();

		/**
		 * Provides or creates and stores new instance of {@link Supplier} that provides access to the {@link TestContext}
		 * in a lazy fashion. When the supplier is created the init/tearDown tests are automatically registered to be
		 * executed.
		 */
		@Nonnull
		public <S extends TestContext, T extends TestContextFactory<S>> Supplier<S> get(@Nonnull Environment profile, @Nonnull Class<T> factoryClass) {
			//noinspection unchecked
			return (Supplier<S>) this.contexts.computeIfAbsent(
				new ContextKey(profile, factoryClass),
				contextKey -> {
					try {
						final Class<?> theFactoryClass = contextKey.factoryClass();
						@SuppressWarnings("unchecked") final TestContextFactory<S> factory = (TestContextFactory<S>) theFactoryClass.getConstructor().newInstance();
						ofNullable(factory.getInitTest(profile)).ifPresent(this.initTests::add);
						ofNullable(factory.getTearDownTest(profile)).ifPresent(this.tearDownTests::add);
						return factory::getContext;
					} catch (Exception e) {
						Assertions.fail(e);
						return null;
					}
				}
			);
		}

		/**
		 * Cache key for {@link TestContextProvider#contexts}.
		 *
		 * @param profile      environment
		 * @param factoryClass factory class
		 */
		record ContextKey(
			@Nonnull Environment profile,
			@Nonnull Class<?> factoryClass
		) {
		}

	}

	/**
	 * Record wraps optional attributes for {@link #SOURCE_CODE_TABS_PATTERN} and {@link #SOURCE_ALTERNATIVE_TABS_PATTERN}.
	 */
	private record Attributes(
		@Nonnull Map<String, String> attributes
	) {
		/**
		 * Attribute that defines scripts that needs to be run prior to this example. But only if it's in the same language.
		 */
		private static final String REQUIRED_SCRIPTS = "requires";
		/**
		 * Attribute that defines scripts that setup the environment for this example. Valid for all example languages.
		 */
		private static final String SETUP_SCRIPTS = "setup";
		/**
		 * Attribute that marks the example that it should be visualized.
		 * Actually is used only by client side JavaScript.
		 */
		private static final String LANGUAGE_SPECIFIC = "langSpecificTabOnly";
		/**
		 * Attribute that marks the example that it's run only on local environment.
		 */
		private static final String LOCAL = "local";
		/**
		 * Attribute that marks temporarily disabled example (i.e. not verified by test suite).
		 * We aim to have ZERO of these in the codebase.
		 */
		private static final String IGNORE_TEST = "ignoreTest";
		/**
		 * Attribute that contains alternative variants of the example.
		 */
		private static final String VARIANTS = "variants";
		private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

		public Attributes(@Nullable String attributes) {
			this(
				attributes == null || attributes.isBlank() ?
					Collections.emptyMap() :
					Arrays.stream(WHITESPACE_PATTERN.split(attributes))
						.map(it -> it.split("="))
						.collect(Collectors.toMap(
							it -> it[0],
							it -> it.length > 1 ? it[1] : ""
						))
			);
		}

		/**
		 * Returns array of setup scripts that needs to be run prior to this example.
		 *
		 * @return array of setup scripts or null if not defined
		 */
		@Nullable
		public Path[] getSetupScripts(@Nonnull Path rootDirectory) {
			return ofNullable(this.attributes.get(SETUP_SCRIPTS))
				.map(String::trim)
				.map(Attributes::removeQuotes)
				.map(
					paths -> Arrays.stream(paths.split(","))
						.filter(it -> !it.isBlank())
						.map(it -> createPathRelativeToRootDirectory(rootDirectory, it).normalize())
						.toArray(Path[]::new)
				)
				.orElse(null);
		}

		/**
		 * Returns array of required scripts that needs to be run prior to this example.
		 *
		 * @return array of required scripts or null if not defined
		 */
		@Nullable
		public Path[] getRequiredScripts(@Nonnull Path rootDirectory) {
			return ofNullable(this.attributes.get(REQUIRED_SCRIPTS))
				.map(String::trim)
				.map(Attributes::removeQuotes)
				.map(
					paths -> Arrays.stream(paths.split(","))
						.filter(it -> !it.isBlank())
						.map(it -> createPathRelativeToRootDirectory(rootDirectory, it).normalize())
						.toArray(Path[]::new)
				)
				.orElse(null);
		}

		/**
		 * Returns true if the example is specific to the language.
		 *
		 * @return true if the example is specific to the language
		 */
		public boolean isLanguageSpecific() {
			return this.attributes.containsKey(LANGUAGE_SPECIFIC);
		}

		/**
		 * Returns true if the example is always run on local environment.
		 *
		 * @return true if the example is always run on local environment
		 */
		public boolean isLocal() {
			return this.attributes.containsKey(LOCAL);
		}

		/**
		 * Returns true if the example is temporarily disabled.
		 *
		 * @return true if the example is temporarily disabled
		 */
		public boolean isIgnoreTest() {
			return this.attributes.containsKey(IGNORE_TEST);
		}

		/**
		 * Returns array of alternative variants of the example.
		 *
		 * @return array of alternative variants or null if not defined
		 */
		@Nullable
		public String[] getVariants() {
			return ofNullable(this.attributes.get(VARIANTS))
				.map(String::trim)
				.map(Attributes::removeQuotes)
				.map(it -> it.split("\\|"))
				.orElse(null);
		}

		/**
		 * Removes quotes from the string.
		 *
		 * @param stringWithQuotes string with quotes
		 * @return string without quotes
		 */
		@Nonnull
		private static String removeQuotes(@Nonnull String stringWithQuotes) {
			if (stringWithQuotes.charAt(0) == '"' && stringWithQuotes.charAt(stringWithQuotes.length() - 1) == '"') {
				return stringWithQuotes.substring(1, stringWithQuotes.length() - 1);
			} else {
				return stringWithQuotes;
			}
		}

	}

}
