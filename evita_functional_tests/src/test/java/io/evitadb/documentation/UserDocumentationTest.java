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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
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
	private static final String DOCS_ROOT = "documentation/user/";
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
					final List<DynamicTest> tests = this.createTests(
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
		return this.createTests(
			Environment.DEMO_SERVER,
			getRootDirectory().resolve("documentation/user/en/use/connectors/grpc.md"),
			new ExampleFilter[]{
				/*ExampleFilter.CSHARP,*/
				ExampleFilter.JAVA,
				ExampleFilter.REST,
				ExampleFilter.GRAPHQL,
				ExampleFilter.EVITAQL
			}
		).stream();
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
	Stream<DynamicTest> testSingleFileDocumentationAndCreateOtherLanguageSnippets() {
		return this.createTests(
			Environment.DEMO_SERVER,
			getRootDirectory().resolve("documentation/user/en/query/requirements/fetching.md"),
			new ExampleFilter[]{
				/*ExampleFilter.CSHARP,*/
				ExampleFilter.JAVA,
				ExampleFilter.REST,
				ExampleFilter.GRAPHQL,
				ExampleFilter.EVITAQL
			},
			CreateSnippets.MARKDOWN, CreateSnippets.JAVA, CreateSnippets.GRAPHQL, CreateSnippets.REST
		).stream();
	}

	/**
	 * Method creates list of {@link DynamicTest} instances for all code blocks in the file of given {@link Path}.
	 * Method returns empty collection if no code block is found.
	 */
	@Nonnull
	private List<DynamicTest> createTests(
		@Nonnull Environment profile,
		@Nonnull Path path,
		@Nonnull ExampleFilter[] exampleFilters,
		@Nonnull CreateSnippets... createSnippets
	) {
		final Path rootDirectory = getRootDirectory();
		// and create an index for them for resolving the dependencies
		final Map<Path, CodeSnippet> codeSnippetIndex = new HashMap<>();
		final Set<String> filteredExtensions = Arrays.stream(exampleFilters)
			.map(ExampleFilter::getExtension)
			.collect(Collectors.toSet());

		final String fileContent = readFileOrThrowException(path);
		final AtomicInteger index = new AtomicInteger();
		final List<CodeSnippet> codeSnippets = new LinkedList<>();
		final TestContextProvider contextAccessor = new TestContextProvider();
		final Set<Path> alreadyUsedPaths = new HashSet<>();

		final Matcher sourceCodeMatcher = SOURCE_CODE_PATTERN.matcher(fileContent);
		while (sourceCodeMatcher.find()) {
			final String format = sourceCodeMatcher.groupCount() > 2 ? sourceCodeMatcher.group(1) : "plain";
			final String content = sourceCodeMatcher.groupCount() > 2 ? sourceCodeMatcher.group(2) : sourceCodeMatcher.group(1);
			if (!(format.isBlank() || NOT_TESTED_LANGUAGES.contains(format))) {
				codeSnippets.add(
					new CodeSnippet(
						"Example #" + index.incrementAndGet(),
						format,
						null,
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
							contextAccessor,
							codeSnippetIndex,
							Collections.emptyList()
						)
					)
				);
			}
		}

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

		final Matcher sourceCodeTabsMatcher = SOURCE_CODE_TABS_PATTERN.matcher(fileContent);
		while (sourceCodeTabsMatcher.find()) {
			final Path referencedFile = createPathRelativeToRootDirectory(rootDirectory, sourceCodeTabsMatcher.group(2));
			final Attributes attributes = new Attributes(sourceCodeTabsMatcher.group(1));
			final String referencedFileExtension = getFileNameExtension(referencedFile);
			if (attributes.isIgnoreTest()) {
				continue;
			}
			final Environment environment = attributes.isLocal() ? Environment.LOCALHOST : profile;
			final SideEffect sideEffect = attributes.isLocal() ? SideEffect.WITH_SIDE_EFFECT : SideEffect.WITHOUT_SIDE_EFFECT;

			if (profile == Environment.LOCALHOST && attributes.isLocal()) {
				// we need to skip the local tests when profile is localhost and the example is local
				// it starts local instance of evitaDB which will fail on already opened ports
				continue;
			}

			if (!NOT_TESTED_LANGUAGES.contains(referencedFileExtension)) {
				alreadyUsedPaths.add(referencedFile);
				final Path[] setupScripts = attributes.getSetupScripts(rootDirectory);
				final Path[] requiredScripts = attributes.getRequiredScripts(rootDirectory);
				final List<OutputSnippet> outputSnippet = ofNullable(outputSnippetIndex.get(referencedFile))
					.orElse(Collections.emptyList());
				final CodeSnippet codeSnippet = new CodeSnippet(
					"Example `" + referencedFile.getFileName() + "`",
					referencedFileExtension,
					referencedFile,
					findRelatedFiles(referencedFile, alreadyUsedPaths, codeSnippetIndex)
						.stream()
						.map(relatedFile -> {
							final String relatedFileExtension = getFileNameExtension(relatedFile);
							return new CodeSnippet(
								"Example `" + relatedFile.getFileName() + "`",
								relatedFileExtension,
								relatedFile,
								null,
								convertToRunnable(
									environment,
									relatedFileExtension,
									readFileOrThrowException(relatedFile),
									rootDirectory,
									relatedFile,
									setupScripts,
									requiredScripts,
									sideEffect,
									contextAccessor,
									codeSnippetIndex,
									ofNullable(
										relatedFileExtension.equals("cs") ?
											outputSnippetIndex.get(Path.of(relatedFile.toString().replace(".cs", ".evitaql"))) :
											outputSnippetIndex.get(relatedFile)
									).orElse(Collections.emptyList()),
									createSnippets
								)
							);
						})
						.toArray(CodeSnippet[]::new),
					convertToRunnable(
						environment,
						referencedFileExtension,
						readFileOrThrowException(referencedFile),
						rootDirectory,
						referencedFile,
						setupScripts,
						requiredScripts,
						sideEffect,
						contextAccessor,
						codeSnippetIndex,
						outputSnippet,
						createSnippets
					)
				);
				codeSnippets.add(codeSnippet);
				codeSnippetIndex.put(codeSnippet.path(), codeSnippet);
			}
		}

		final Matcher sourceAlternativeTabsMatcher = SOURCE_ALTERNATIVE_TABS_PATTERN.matcher(fileContent);
		while (sourceAlternativeTabsMatcher.find()) {
			final Path referencedFile = createPathRelativeToRootDirectory(rootDirectory, sourceAlternativeTabsMatcher.group(2));
			final String referencedFileExtension = getFileNameExtension(referencedFile);
			final Attributes attributes = new Attributes(sourceAlternativeTabsMatcher.group(1));
			final Path[] setupScripts = attributes.getSetupScripts(rootDirectory);
			final Path[] requiredScripts = attributes.getRequiredScripts(rootDirectory);
			final SideEffect sideEffect = attributes.isLocal() ? SideEffect.WITH_SIDE_EFFECT : SideEffect.WITHOUT_SIDE_EFFECT;
			final String[] variants = attributes.getVariants();
			if (!NOT_TESTED_LANGUAGES.contains(referencedFileExtension)) {
				alreadyUsedPaths.add(referencedFile);
				final List<OutputSnippet> outputSnippet = ofNullable(outputSnippetIndex.get(referencedFile))
					.orElse(Collections.emptyList());
				final CodeSnippet codeSnippet = new CodeSnippet(
					"Example `" + referencedFile.getFileName() + "`",
					referencedFileExtension,
					referencedFile,
					findVariants(referencedFile, alreadyUsedPaths, variants)
						.stream()
						.map(relatedFile -> {
							final String relatedFileExtension = getFileNameExtension(relatedFile);
							return new CodeSnippet(
								"Example `" + relatedFile.getFileName() + "`",
								relatedFileExtension,
								relatedFile,
								null,
								convertToRunnable(
									profile,
									relatedFileExtension,
									readFileOrThrowException(relatedFile),
									rootDirectory,
									relatedFile,
									setupScripts,
									requiredScripts,
									sideEffect,
									contextAccessor,
									codeSnippetIndex,
									ofNullable(
										relatedFileExtension.equals("cs") ?
											outputSnippetIndex.get(Path.of(relatedFile.toString().replace(".cs", ".evitaql"))) :
											outputSnippetIndex.get(relatedFile)
									).orElse(Collections.emptyList()),
									createSnippets
								)
							);
						})
						.toArray(CodeSnippet[]::new),
					convertToRunnable(
						profile,
						referencedFileExtension,
						readFileOrThrowException(referencedFile),
						rootDirectory,
						referencedFile,
						setupScripts,
						requiredScripts,
						sideEffect,
						contextAccessor,
						codeSnippetIndex,
						outputSnippet,
						createSnippets
					)
				);
				codeSnippets.add(codeSnippet);
				codeSnippetIndex.put(codeSnippet.path(), codeSnippet);
			}
		}

		// return tests if some code blocks were found
		return codeSnippets.isEmpty() ?
			Collections.emptyList() :
			Stream.of(
					// always start with context instance initialization
					contextAccessor.getInitTests().stream(),
					// execute tests in between
					codeSnippets.stream()
						.flatMap(
							it -> Stream.concat(
								Stream.of(it),
								ofNullable(it.relatedSnippets()).stream().flatMap(Arrays::stream)
							)
						)
						.filter(it -> filteredExtensions.contains(it.format()))
						.map(
							codeSnippet ->
								dynamicTest(
									// use the name from the code snippet for the name of the test
									codeSnippet.name(),
									// if the code snippet refers to an external file, link it here, so that clicks work in IDE
									ofNullable(codeSnippet.path()).map(Path::toUri).orElse(null),
									// then execute the snippet
									() -> codeSnippet.executableLambda().execute()
								)
						),
					// always finish with context instance tear down
					contextAccessor.getTearDownTests().stream()
				)
				.flatMap(Function.identity())
				.toList();
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
		@Nullable CodeSnippet[] relatedSnippets,
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

		public Attributes(@Nullable String attributes) {
			this(
				attributes == null || attributes.isBlank() ?
					Collections.emptyMap() :
					Arrays.stream(attributes.split("\\s+"))
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
