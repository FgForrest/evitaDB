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

package io.evitadb.documentation.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.documentation.JsonExecutable;
import io.evitadb.documentation.UserDocumentationTest.CreateSnippets;
import io.evitadb.documentation.UserDocumentationTest.OutputSnippet;
import io.evitadb.documentation.markdown.CustomCodeBlock;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.client.GraphQLClient;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;
import net.steppschuh.markdowngenerator.MarkdownSerializationException;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.evitadb.documentation.UserDocumentationTest.readFile;
import static io.evitadb.documentation.UserDocumentationTest.resolveSiblingWithDifferentExtension;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The implementation of the GraphQL source code dynamic test verifying single GraphQL example from the documentation.
 * GraphQL needs to be successfully parsed, executed via {@link GraphQLClient} against our demo server and its result
 * must match the previously frozen content in the MarkDown table of the same name.
 *
 * The executor can also generate {@link CreateSnippets#MARKDOWN} if requested. This
 * feature is used for getting the result in MarkDown format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class GraphQLExecutable extends JsonExecutable implements Executable, EvitaTestSupport {

	/**
	 * Pattern of keywords for identify system instance.
	 */
	private static final Pattern SYSTEM_INSTANCE_KEYWORDS_PATTERN = Pattern.compile("(createCatalog|switchCatalogToAliveState)");
	/**
	 * Pattern of keywords for identify schema instance.
	 */
	private static final Pattern SCHEMA_INSTANCE_KEYWORDS_PATTERN = Pattern.compile("(update[a-zA-Z]+Schema\\()");

	/**
	 * Provides access to the {@link GraphQLTestContext} instance.
	 */
	private final @Nonnull Supplier<GraphQLTestContext> testContextAccessor;
	/**
	 * Contains the tested EvitaQL code.
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
	 * Contains reference to the output snippet bound to this executable.
	 */
	private final @Nullable List<OutputSnippet> outputSnippet;
	/**
	 * Contains requests for generating alternative language code snippets.
	 */
	private final @Nonnull CreateSnippets[] createSnippets;

	/**
	 * Method writes new content to the file with same name as original file but a different extension.
	 *
	 * @param originalFile original file name to copy name and location from
	 * @param extension    new extension of the file
	 * @param fileContent  new content of the file
	 */
	private static void writeFile(@Nonnull Path originalFile, @Nonnull String extension, @Nonnull String fileContent) {
		final Path writeFileName = resolveSiblingWithDifferentExtension(originalFile, extension);
		writeFile(writeFileName, fileContent);
	}

	/**
	 * Method writes new content to the file.
	 *
	 * @param targetFile  original file name to copy name and location from
	 * @param fileContent new content of the file
	 */
	private static void writeFile(@Nonnull Path targetFile, @Nonnull String fileContent) {
		try {
			Files.writeString(
				targetFile, fileContent,
				StandardCharsets.UTF_8,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE
			);
		} catch (IOException e) {
			fail(e);
		}
	}

	/**
	 * Generates the MarkDown output with the results of the given query.
	 */
	@Nonnull
	private static String generateMarkdownSnippet(
		@Nonnull JsonNode response,
		@Nullable OutputSnippet outputSnippet
	) {
		final String outputFormat = ofNullable(outputSnippet).map(OutputSnippet::forFormat).orElse("json");
		if (outputFormat.equals("json")) {
			final String sourceVariable = ofNullable(outputSnippet).map(OutputSnippet::sourceVariable).orElse(null);
			return generateMarkDownJsonBlock(response, sourceVariable);
		} else {
			throw new UnsupportedOperationException("Unsupported output format: " + outputFormat);
		}
	}

	/**
	 * Transforms content that matches the sourceVariable into a JSON and puts it into the MarkDown code block.
	 */
	@Nonnull
	private static String generateMarkDownJsonBlock(
		@Nonnull JsonNode response,
		@Nullable String sourceVariable
	) {
		final JsonNode theValue;
		if (sourceVariable == null || sourceVariable.isBlank()) {
			theValue = response;
		} else {
			final String[] sourceVariableParts = sourceVariable.split("\\.");
			theValue = extractValueFrom(response, sourceVariableParts);
		}
		Assert.isPremiseValid(
			theValue != null,
			"Result value is null for sourceVariable `" + sourceVariable + "`. This is strange."
		);
		final String json = of(theValue)
			.map(it -> {
				try {
					return OBJECT_MAPPER.writer(DEFAULT_PRETTY_PRINTER).writeValueAsString(it);
				} catch (JsonProcessingException e) {
					fail(e);
					return "";
				}
			})
			.orElse("");

		try {
			return new CustomCodeBlock(json, "json").serialize();
		} catch (MarkdownSerializationException e) {
			fail(e);
			return "";
		}
	}

	/**
	 * Method heuristically traverses `theObjects` and extracts its internals by applying `sourceVariableParts`.
	 */
	@Nullable
	private static JsonNode extractValueFrom(@Nonnull JsonNode theObject, @Nonnull String[] sourceVariableParts) {
		if (theObject instanceof ObjectNode objectNode) {
			final JsonNode node = objectNode.get(sourceVariableParts[0]);
			if (sourceVariableParts.length > 1) {
				return extractValueFrom(
					node,
					Arrays.copyOfRange(sourceVariableParts, 1, sourceVariableParts.length)
				);
			} else {
				return node;
			}
		}
		return null;
	}

	@Override
	public void execute() throws Throwable {
		final String theQuery = this.sourceContent;

		final String instancePath;
		if (SYSTEM_INSTANCE_KEYWORDS_PATTERN.matcher(theQuery).find()) {
			instancePath = "/gql/system";
		} else if (SCHEMA_INSTANCE_KEYWORDS_PATTERN.matcher(theQuery).find()) {
			instancePath = "/gql/evita/schema";
		} else {
			instancePath = "/gql/evita";
		}

		final GraphQLClient graphQLClient = this.testContextAccessor.get().getGraphQLClient();
		final JsonNode theResult;
		try {
			theResult = graphQLClient.call(instancePath, theQuery).orElseThrow();
		} catch (Exception ex) {
			fail("The query " + theQuery + " failed: " + ex.getMessage(), ex);
			return;
		}

		if (this.resource != null) {
			final List<String> markdownSnippets = this.outputSnippet.stream()
				.map(snippet -> generateMarkdownSnippet(theResult, snippet))
				.toList();

			// generate Markdown snippet from the result if required
			for (int i = 0; i < this.outputSnippet.size(); i++) {
				final OutputSnippet snippet = this.outputSnippet.get(i);
				final String markdownSnippet = markdownSnippets.get(i);

				final String outputFormat = ofNullable(snippet).map(OutputSnippet::forFormat).orElse("json");
				if (Arrays.stream(this.createSnippets).anyMatch(it -> it == CreateSnippets.MARKDOWN)) {
					if (snippet == null) {
						writeFile(this.resource, outputFormat, markdownSnippet);
					} else {
						writeFile(snippet.path(), markdownSnippet);
					}
				}

				// assert MarkDown file contents
				final Optional<String> markDownFile = snippet == null ?
					readFile(this.resource, outputFormat) : readFile(snippet.path());
				markDownFile.ifPresent(
					content -> {
						assertEquals(
							content.trim(),
							markdownSnippet.trim()
						);

						final Path assertSource = snippet == null ?
							resolveSiblingWithDifferentExtension(this.resource, outputFormat).normalize() :
							snippet.path().normalize();

						final String relativePath = assertSource.toString().substring(this.rootDirectory.normalize().toString().length());
						System.out.println("Markdown snippet `" + relativePath + "` contents verified OK. \uD83D\uDE0A");
					}
				);
			}
		}
	}
}
