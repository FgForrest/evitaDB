/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.documentation.javadoc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaAnnotation;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;
import com.thoughtworks.qdox.model.JavaParameter;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.test.EvitaTestSupport;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maven-invocable tool that uses OpenAI API to generate concise factory method summaries from the verbose constraint
 * class JavaDoc in {@link io.evitadb.api.query.QueryConstraints}. The tool is incremental — only
 * regenerating summaries when the source JavaDoc or method signature has changed (tracked via
 * {@code @SourceHash} annotation).
 *
 * Invoked via: {@code mvn -pl evita_test/evita_functional_tests exec:java -Pgenerate-javadoc}
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class JavaDocSummarizer implements EvitaTestSupport {

	private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
	private static final String OPENAI_MODEL = "gpt-4.1";
	private static final int MAX_PARALLEL_REQUESTS = 4;
	private static final double TEMPERATURE = 0.3;
	private static final int MAX_TOKENS = 500;
	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);

	private static final String[] CONSTRAINTS_ROOT = {
		"evita_query/src/main/java/io/evitadb/api/query/head",
		"evita_query/src/main/java/io/evitadb/api/query/filter",
		"evita_query/src/main/java/io/evitadb/api/query/order",
		"evita_query/src/main/java/io/evitadb/api/query/require"
	};
	private static final String QUERY_CONSTRAINTS_PATH =
		"evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java";
	private static final String QUERY_CONSTRAINTS_CLASS = "io.evitadb.api.query.QueryConstraints";

	private static final String SYSTEM_PROMPT = """
		You are a technical writer for evitaDB, a specialized NoSQL database for e-commerce.
		Your task is to write a concise JavaDoc summary for a factory method based on the
		detailed constraint class documentation provided.

		Rules:
		- Output ONLY the JavaDoc body text (no /** or */ delimiters, no leading * prefixes).
		- Use Markdown syntax for formatting (never use HTML tags).
		- Write a SINGLE paragraph (Twitter-length, ~280 chars) describing what the constraint
		  does, its key semantics, and any critical behavioral notes.
		- After the paragraph, if the constraint benefits from a short usage example, include
		  ONE brief EvitaQL code block (3-5 lines max) showing the most common usage pattern.
		  Use ```evitaql fenced code block syntax. Skip the example if the constraint is
		  self-explanatory from the paragraph alone.
		- End with a link to detailed docs in this exact Markdown format:
		  [Visit detailed user documentation](https://evitadb.io{userDocsLink})
		- Do NOT include @param, @return, @see, or @author tags.
		- Do NOT include section headers or bullet lists.
		""";

	private static final String OPENAI_API_KEY_ENV = "OPENAI_API_KEY";
	private static final String LIMIT_ARG_PREFIX = "--limit=";
	private static final String SOURCE_HASH_ANNOTATION_NAME = "SourceHash";

	/**
	 * Entry point invoked via exec-maven-plugin.
	 */
	public static void main(@Nonnull String[] args) throws Exception {
		final JavaDocSummarizer summarizer = new JavaDocSummarizer();
		final Path rootDir = summarizer.getRootDirectory();
		final Path queryConstraintsPath = rootDir.resolve(QUERY_CONSTRAINTS_PATH);

		final int limit;
		if (args.length > 0 && args[0].startsWith(LIMIT_ARG_PREFIX)) {
			limit = Integer.parseInt(args[0].substring(LIMIT_ARG_PREFIX.length()));
			System.out.println("Limiting to first " + limit + " methods requiring regeneration.");
		} else {
			limit = Integer.MAX_VALUE;
		}

		// Step 1: Parse source and collect method info
		System.out.println("Parsing source files...");
		final List<MethodInfo> allMethods = collectMethodInfo(queryConstraintsPath, rootDir);
		System.out.println("Found " + allMethods.size() + " factory methods with matching constraint classes.");

		// Step 2: Filter to methods needing regeneration
		final List<MethodInfo> methodsToRegenerate = new ArrayList<>(allMethods.size());
		for (MethodInfo method : allMethods) {
			final String currentHash = computeHash(method.constraintJavaDoc(), method.methodSignature());
			if (!currentHash.equals(method.existingSourceHash())) {
				methodsToRegenerate.add(method);
			}
		}
		System.out.println(methodsToRegenerate.size() + " methods need regeneration.");

		if (methodsToRegenerate.isEmpty()) {
			System.out.println("All summaries are up to date. Nothing to do.");
			return;
		}

		// Apply limit
		final List<MethodInfo> methodsToProcess;
		if (methodsToRegenerate.size() > limit) {
			methodsToProcess = methodsToRegenerate.subList(0, limit);
			System.out.println("Processing first " + limit + " methods due to --limit flag.");
		} else {
			methodsToProcess = methodsToRegenerate;
		}

		// Step 3: Generate summaries via OpenAI
		final String apiKey = System.getenv(OPENAI_API_KEY_ENV);
		if (apiKey == null || apiKey.isBlank()) {
			System.err.println("ERROR: " + OPENAI_API_KEY_ENV + " environment variable is not set.");
			System.exit(1);
		}

		System.out.println("Generating summaries via OpenAI (" + OPENAI_MODEL + ")...");
		final Map<MethodInfo, String> summaries = generateSummaries(methodsToProcess, apiKey);
		System.out.println("Generated " + summaries.size() + " summaries.");

		// Step 4: Rewrite QueryConstraints.java
		System.out.println("Rewriting " + QUERY_CONSTRAINTS_PATH + "...");
		rewriteQueryConstraints(queryConstraintsPath, summaries);
		System.out.println("Done. Updated " + summaries.size() + " factory method JavaDocs.");
	}

	/**
	 * Parses constraint source folders and QueryConstraints.java via QDox, collecting information about each
	 * factory method that has a matching constraint class.
	 */
	@Nonnull
	private static List<MethodInfo> collectMethodInfo(
		@Nonnull Path queryConstraintsPath,
		@Nonnull Path rootDir
	) throws IOException {
		final JavaProjectBuilder builder = new JavaProjectBuilder();

		for (String constraintRoot : CONSTRAINTS_ROOT) {
			builder.addSourceTree(rootDir.resolve(constraintRoot).toFile());
		}
		builder.addSource(queryConstraintsPath.toFile());

		final JavaClass queryConstraints = builder.getClassByName(QUERY_CONSTRAINTS_CLASS);

		final Map<String, JavaClass> classesByName = builder.getClasses()
			.stream()
			.collect(Collectors.toMap(JavaClass::getName, Function.identity(), (a, b) -> a));

		final List<JavaMethod> factoryMethods = queryConstraints.getMethods();
		final List<MethodInfo> result = new ArrayList<>(factoryMethods.size());

		for (JavaMethod method : factoryMethods) {
			if (!method.isStatic()) {
				continue;
			}

			final JavaClass referencedConstraint = classesByName.get(method.getReturns().getName());
			if (referencedConstraint == null) {
				continue;
			}

			// only process actual constraint classes with @ConstraintDefinition
			boolean hasConstraintDefinition = false;
			for (JavaAnnotation annotation : referencedConstraint.getAnnotations()) {
				if (annotation.getType().getName().equals(ConstraintDefinition.class.getSimpleName())) {
					hasConstraintDefinition = true;
					break;
				}
			}
			if (!hasConstraintDefinition) {
				continue;
			}

			final String constraintJavaDoc = referencedConstraint.getComment();
			if (constraintJavaDoc == null || constraintJavaDoc.isBlank()) {
				continue;
			}

			final String constraintClassName = referencedConstraint.getFullyQualifiedName();
			final String userDocsLink = extractUserDocsLink(referencedConstraint);
			final String methodSignature = buildMethodSignature(method);
			final String existingHash = extractExistingSourceHash(method);

			result.add(new MethodInfo(
				method.getLineNumber(),
				methodSignature,
				constraintJavaDoc,
				constraintClassName,
				userDocsLink,
				existingHash
			));
		}

		return result;
	}

	/**
	 * Extracts the {@code userDocsLink} value from the {@code @ConstraintDefinition} annotation on the given class.
	 */
	@Nonnull
	static String extractUserDocsLink(@Nonnull JavaClass constraintClass) {
		for (JavaAnnotation annotation : constraintClass.getAnnotations()) {
			if (annotation.getType().getName().equals(ConstraintDefinition.class.getSimpleName())) {
				final Object link = annotation.getNamedParameter("userDocsLink");
				if (link != null) {
					return link.toString().replace("\"", "");
				}
			}
		}
		return "";
	}

	/**
	 * Builds a human-readable method signature string from a QDox method model.
	 */
	@Nonnull
	static String buildMethodSignature(@Nonnull JavaMethod method) {
		final StringBuilder sb = new StringBuilder(128);
		sb.append(method.getReturns().getName());
		sb.append(' ');
		sb.append(method.getName());
		sb.append('(');
		final List<JavaParameter> params = method.getParameters();
		for (int i = 0; i < params.size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			final JavaParameter param = params.get(i);
			sb.append(param.getType().getValue());
			if (param.isVarArgs()) {
				sb.append("...");
			}
			sb.append(' ');
			sb.append(param.getName());
		}
		sb.append(')');
		return sb.toString();
	}

	/**
	 * Extracts the existing {@code @SourceHash} annotation value from a factory method, if present.
	 */
	@Nullable
	static String extractExistingSourceHash(@Nonnull JavaMethod method) {
		for (JavaAnnotation annotation : method.getAnnotations()) {
			if (annotation.getType().getName().equals(SOURCE_HASH_ANNOTATION_NAME)) {
				final Object value = annotation.getNamedParameter("value");
				if (value != null) {
					return value.toString().replace("\"", "");
				}
				// single-element annotation — QDox may return it as the default property
				final Object defaultValue = annotation.getProperty("value");
				if (defaultValue != null) {
					return defaultValue.toString().replace("\"", "");
				}
			}
		}
		return null;
	}

	/**
	 * Computes an MD5 hex hash of the constraint JavaDoc concatenated with the method signature.
	 */
	@Nonnull
	static String computeHash(@Nonnull String constraintJavaDoc, @Nonnull String methodSignature) {
		try {
			final MessageDigest md = MessageDigest.getInstance("MD5");
			final byte[] digest = md.digest(
				(constraintJavaDoc + "|" + methodSignature).getBytes(StandardCharsets.UTF_8)
			);
			final StringBuilder hex = new StringBuilder(32);
			for (byte b : digest) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 algorithm not available", e);
		}
	}

	/**
	 * Generates concise JavaDoc summaries for the given methods via parallel OpenAI API calls.
	 */
	@Nonnull
	private static Map<MethodInfo, String> generateSummaries(
		@Nonnull List<MethodInfo> methods,
		@Nonnull String apiKey
	) {
		final ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS);
		final Semaphore semaphore = new Semaphore(MAX_PARALLEL_REQUESTS);
		final AtomicInteger counter = new AtomicInteger(0);
		final int total = methods.size();
		final ObjectMapper objectMapper = new ObjectMapper();

		final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(HTTP_TIMEOUT)
			.build();

		try {
			final List<CompletableFuture<Map.Entry<MethodInfo, String>>> futures = new ArrayList<>(methods.size());

			for (MethodInfo method : methods) {
				final CompletableFuture<Map.Entry<MethodInfo, String>> future =
					CompletableFuture.supplyAsync(() -> {
						try {
							semaphore.acquire();
							try {
								final String summary = callOpenAI(httpClient, objectMapper, apiKey, method);
								final int done = counter.incrementAndGet();
								System.out.println("[" + done + "/" + total + "] Generated summary for: "
									+ method.methodSignature());
								return Map.entry(method, summary);
							} finally {
								semaphore.release();
							}
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new RuntimeException("Interrupted while waiting for semaphore", e);
						}
					}, executor);
				futures.add(future);
			}

			final Map<MethodInfo, String> results = new LinkedHashMap<>(methods.size());
			for (CompletableFuture<Map.Entry<MethodInfo, String>> future : futures) {
				try {
					final Map.Entry<MethodInfo, String> entry = future.join();
					results.put(entry.getKey(), entry.getValue());
				} catch (Exception e) {
					System.err.println("WARNING: Failed to generate summary — " + e.getMessage());
				}
			}
			return results;
		} finally {
			executor.shutdown();
		}
	}

	/**
	 * Calls the OpenAI chat completions API for a single method and returns the generated summary text.
	 */
	@Nonnull
	private static String callOpenAI(
		@Nonnull HttpClient httpClient,
		@Nonnull ObjectMapper objectMapper,
		@Nonnull String apiKey,
		@Nonnull MethodInfo method
	) {
		try {
			final String userMessage = "Constraint class JavaDoc:\n---\n" + method.constraintJavaDoc()
				+ "\n---\n\nFactory method signature:\n" + method.methodSignature()
				+ "\n\nUser documentation link path: " + method.userDocsLink();

			final String requestBody = objectMapper.writeValueAsString(Map.of(
				"model", OPENAI_MODEL,
				"temperature", TEMPERATURE,
				"max_tokens", MAX_TOKENS,
				"messages", List.of(
					Map.of("role", "system", "content", SYSTEM_PROMPT),
					Map.of("role", "user", "content", userMessage)
				)
			));

			final HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(OPENAI_API_URL))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.timeout(HTTP_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
				.build();

			final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				throw new IOException("OpenAI API returned status " + response.statusCode() + ": " + response.body());
			}

			final JsonNode root = objectMapper.readTree(response.body());
			final String content = root.path("choices").path(0).path("message").path("content").asText().trim();
			if (content.isEmpty()) {
				throw new IOException("OpenAI API returned empty content for method: " + method.methodSignature());
			}
			return content;
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException("OpenAI API call failed for method: " + method.methodSignature(), e);
		}
	}

	/**
	 * Rewrites QueryConstraints.java, replacing JavaDoc blocks and inserting/updating {@code @SourceHash} annotations
	 * for methods that have new summaries. Processes methods from bottom to top to avoid line offset drift.
	 */
	static void rewriteQueryConstraints(
		@Nonnull Path path,
		@Nonnull Map<MethodInfo, String> summaries
	) throws IOException {
		final List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));

		// Sort by method declaration line descending — process bottom-to-top
		final List<Map.Entry<MethodInfo, String>> sortedEntries = summaries.entrySet()
			.stream()
			.sorted(Comparator.<Map.Entry<MethodInfo, String>>comparingInt(e -> e.getKey().methodDeclLine()).reversed())
			.toList();

		for (Map.Entry<MethodInfo, String> entry : sortedEntries) {
			final MethodInfo method = entry.getKey();
			final String summary = entry.getValue();
			final String newHash = computeHash(method.constraintJavaDoc(), method.methodSignature());

			// QDox line numbers are 1-based, list is 0-based
			final int methodDeclIdx = method.methodDeclLine() - 1;

			// Find the JavaDoc block above the method declaration
			int javadocEndIdx = -1;
			int javadocStartIdx = -1;
			for (int i = methodDeclIdx - 1; i >= 0; i--) {
				final String trimmed = lines.get(i).trim();
				if (javadocEndIdx == -1) {
					// Skip annotations and blank lines between method and JavaDoc end
					if (trimmed.startsWith("@") || trimmed.isEmpty()) {
						continue;
					}
					if (trimmed.endsWith("*/")) {
						javadocEndIdx = i;
					} else {
						break; // no JavaDoc found
					}
				}
				if (trimmed.startsWith("/**")) {
					javadocStartIdx = i;
					break;
				}
			}

			if (javadocStartIdx == -1 || javadocEndIdx == -1) {
				System.err.println("WARNING: Could not locate JavaDoc for method at line " + method.methodDeclLine()
					+ " (" + method.methodSignature() + "). Skipping.");
				continue;
			}

			// Find existing @SourceHash annotation between JavaDoc end and method declaration
			int sourceHashIdx = -1;
			for (int i = javadocEndIdx + 1; i < methodDeclIdx; i++) {
				if (lines.get(i).trim().startsWith("@SourceHash")) {
					sourceHashIdx = i;
					break;
				}
			}

			// Build the new JavaDoc block
			final List<String> newJavaDoc = formatJavaDoc(summary, method.constraintClassName());

			// Build the @SourceHash annotation line
			final String sourceHashLine = "\t@SourceHash(\"" + newHash + "\")";

			// Remove @SourceHash line first (below JavaDoc, so indices still valid for JavaDoc removal)
			if (sourceHashIdx != -1) {
				lines.remove(sourceHashIdx);
			}

			// Remove the JavaDoc block (javadocStartIdx..javadocEndIdx inclusive)
			for (int i = javadocEndIdx; i >= javadocStartIdx; i--) {
				lines.remove(i);
			}

			// Insert new content at javadocStartIdx position: JavaDoc + @SourceHash
			int insertIdx = javadocStartIdx;
			for (String line : newJavaDoc) {
				lines.add(insertIdx++, line);
			}
			lines.add(insertIdx, sourceHashLine);
		}

		Files.writeString(path, String.join("\n", lines) + "\n", StandardOpenOption.TRUNCATE_EXISTING);
	}

	/**
	 * Formats a summary string into a JavaDoc block with tab-indented lines.
	 */
	@Nonnull
	static List<String> formatJavaDoc(@Nonnull String summary, @Nonnull String constraintClassName) {
		final List<String> result = new ArrayList<>(16);
		result.add("\t/**");
		for (String line : summary.split("\\r?\\n")) {
			if (line.isEmpty()) {
				result.add("\t *");
			} else {
				result.add("\t * " + line.replace("*/", "* /"));
			}
		}
		result.add("\t *");
		result.add("\t * @see " + constraintClassName);
		result.add("\t */");
		return result;
	}

	/**
	 * Holds information about a single factory method in QueryConstraints that has a matching constraint class.
	 *
	 * @param methodDeclLine      the 1-based line number where the method declaration starts
	 * @param methodSignature     human-readable method signature (e.g. "And and(FilterConstraint... constraints)")
	 * @param constraintJavaDoc   full JavaDoc text from the constraint class
	 * @param constraintClassName fully-qualified name of the constraint class (used for {@code @see} tag)
	 * @param userDocsLink        relative URL path from {@code @ConstraintDefinition}
	 * @param existingSourceHash  current {@code @SourceHash} value, or null if absent
	 */
	record MethodInfo(
		int methodDeclLine,
		@Nonnull String methodSignature,
		@Nonnull String constraintJavaDoc,
		@Nonnull String constraintClassName,
		@Nonnull String userDocsLink,
		@Nullable String existingSourceHash
	) {
	}

}
