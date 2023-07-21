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

package io.evitadb.documentation.rest;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.documentation.UserDocumentationTest.CreateSnippets;
import io.evitadb.documentation.UserDocumentationTest.OutputSnippet;
import io.evitadb.documentation.evitaql.CustomJsonVisibilityChecker;
import io.evitadb.documentation.evitaql.EntityDocumentationJsonSerializer;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.client.RestClient;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;
import net.steppschuh.markdowngenerator.MarkdownSerializationException;
import net.steppschuh.markdowngenerator.text.code.CodeBlock;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.documentation.UserDocumentationTest.readFile;
import static io.evitadb.documentation.UserDocumentationTest.resolveSiblingWithDifferentExtension;
import static io.evitadb.documentation.evitaql.CustomJsonVisibilityChecker.allow;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The implementation of the REST source code dynamic test verifying single REST example from the documentation.
 * REST needs to be successfully parsed, executed via {@link io.evitadb.test.client.RestClient} against our demo server and its result
 * must match the previously frozen content in the MarkDown table of the same name.
 *
 * The executor can also generate {@link CreateSnippets#MARKDOWN} if requested. This
 * feature is used for getting the result in MarkDown format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class RestExecutable implements Executable, EvitaTestSupport {
	/**
	 * Object mapper used to serialize unknown objects to JSON output.
	 */
	private final static ObjectMapper OBJECT_MAPPER;
	/**
	 * Pretty printer used to format JSON output.
	 */
	private final static DefaultPrettyPrinter DEFAULT_PRETTY_PRINTER;

	/**
	 * Regex pattern to parse input request into URL and query (request body)
	 */
	private static final Pattern REQUEST_PATTERN = Pattern.compile("([A-Z]+)\\s((/[\\w\\-]+)+)(\\s+([.\\s\\S]+))?");

	/*
	  Initializes the Java code template to be used when {@link CreateSnippets#JAVA} is requested.
	 */
	static {
		OBJECT_MAPPER = new ObjectMapper();
		OBJECT_MAPPER.setVisibility(
			new CustomJsonVisibilityChecker(
				allow(EntityClassifier.class),
				allow(EntityClassifierWithParent.class),
				allow(Hierarchy.class),
				allow(Hierarchy.LevelInfo.class)
			)
		);
		OBJECT_MAPPER.registerModule(new Jdk8Module());
		OBJECT_MAPPER.registerModule(
			new SimpleModule()
				.addSerializer(EntityContract.class, new EntityDocumentationJsonSerializer()));

		OBJECT_MAPPER.setSerializationInclusion(Include.NON_DEFAULT);
		OBJECT_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
		OBJECT_MAPPER.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		OBJECT_MAPPER.enable(SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS);

		DEFAULT_PRETTY_PRINTER = new DefaultPrettyPrinter();
		DEFAULT_PRETTY_PRINTER.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
		DEFAULT_PRETTY_PRINTER.indentObjectsWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
	}

	/**
	 * Provides access to the {@link RestTestContext} instance.
	 */
	private final @Nonnull Supplier<RestTestContext> testContextAccessor;
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
	private final @Nullable OutputSnippet outputSnippet;
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
		boolean shouldHaveResult,
		@Nonnull JsonNode response,
		@Nullable OutputSnippet outputSnippet
	) {
		final String outputFormat = ofNullable(outputSnippet).map(OutputSnippet::forFormat).orElse("json");
		if (outputFormat.equals("json")) {
			final String sourceVariable = ofNullable(outputSnippet).map(OutputSnippet::sourceVariable).orElse(null);
			return generateMarkDownJsonBlock(shouldHaveResult, response, sourceVariable);
		} else {
			throw new UnsupportedOperationException("Unsupported output format: " + outputFormat);
		}
	}

	/**
	 * Transforms content that matches the sourceVariable into a JSON and puts it into the MarkDown code block.
	 */
	@Nonnull
	private static String generateMarkDownJsonBlock(
		boolean shouldHaveResult,
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
		if (shouldHaveResult) {
			Assert.isPremiseValid(
				theValue != null,
				"Expected non-empty result but result value is null for sourceVariable `" + sourceVariable + "`. This is strange."
			);
		} else {
			Assert.isPremiseValid(
				theValue == null,
				"Expected empty result but result value is not null for sourceVariable `" + sourceVariable + "`. This is strange."
			);
		}
		final String json = ofNullable(theValue)
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
			return new CodeBlock(json, "json").serialize();
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
		final Matcher request = REQUEST_PATTERN.matcher(sourceContent);
		Assert.isPremiseValid(request.matches(), "Invalid request format.");
		final String method = request.group(1);
		final String path = request.group(2);
		final String theQuery = request.group(5);
		final boolean shouldHaveResult = Set.of("POST", "PUT", "GET").contains(method);

		final RestClient restClient = testContextAccessor.get().getRestClient();
		final JsonNode theResult;
		try {
			theResult = restClient.call(method, path, theQuery).orElseThrow();
		} catch (Exception ex) {
			fail("The query " + theQuery + " failed: " + ex.getMessage(), ex);
			return;
		}

		if (resource != null) {
			final String markdownSnippet = generateMarkdownSnippet(shouldHaveResult, theResult, outputSnippet);

			// generate Markdown snippet from the result if required
			final String outputFormat = ofNullable(outputSnippet).map(OutputSnippet::forFormat).orElse("json");
			if (Arrays.stream(createSnippets).anyMatch(it -> it == CreateSnippets.MARKDOWN)) {
				if (outputSnippet == null) {
					writeFile(resource, outputFormat, markdownSnippet);
				} else {
					writeFile(outputSnippet.path(), markdownSnippet);
				}
			}

			// assert MarkDown file contents
			final Optional<String> markDownFile = outputSnippet == null ?
				readFile(resource, outputFormat) : readFile(outputSnippet.path());
			markDownFile.ifPresent(
				content -> {
					assertEquals(
						content,
						markdownSnippet
					);

					final Path assertSource = outputSnippet == null ?
						resolveSiblingWithDifferentExtension(resource, outputFormat).normalize() :
						outputSnippet.path().normalize();

					final String relativePath = assertSource.toString().substring(rootDirectory.normalize().toString().length());
					System.out.println("Markdown snippet `" + relativePath + "` contents verified OK. \uD83D\uDE0A");
				}
			);
		}
	}
}
