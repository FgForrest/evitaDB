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

package io.evitadb.documentation.graphql;

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
import io.evitadb.driver.EvitaClient;
import io.evitadb.test.EvitaTestSupport;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.evitadb.documentation.UserDocumentationTest.readFile;
import static io.evitadb.documentation.UserDocumentationTest.resolveSiblingWithDifferentExtension;
import static io.evitadb.documentation.evitaql.CustomJsonVisibilityChecker.allow;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The implementation of the EvitaQL source code dynamic test verifying single EvitaQL example from the documentation.
 * EvitaQL needs to be successfully parsed, executed via {@link EvitaClient} against our demo server and its result
 * must match the previously frozen content in the MarkDown table of the same name.
 *
 * The executor can also generate {@link CreateSnippets#JAVA} and {@link CreateSnippets#MARKDOWN} if requested. This
 * feature is used when the documentation is written to avoid unnecessary work of translating the EvitaQL to Java
 * source code (which is straightforward) and getting the result in MarkDown format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class GraphQLExecutable implements Executable, EvitaTestSupport {
	/**
	 * Object mapper used to serialize unknown objects to JSON output.
	 */
	private final static ObjectMapper OBJECT_MAPPER;
	/**
	 * Pretty printer used to format JSON output.
	 */
	private final static DefaultPrettyPrinter DEFAULT_PRETTY_PRINTER;

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
		@Nonnull JsonNode response,
		@Nullable OutputSnippet outputSnippet
	) {
		final String outputFormat = ofNullable(outputSnippet).map(OutputSnippet::forFormat).orElse("json");
		if (outputFormat.equals("json")) {
			final String sourceVariable = outputSnippet.sourceVariable();
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
		final String theQuery = sourceContent;
		final GraphQLClient graphQLClient = testContextAccessor.get().getGraphQLClient();
		final JsonNode theResult;
		try {
			theResult = graphQLClient.call(theQuery);
		} catch (Exception ex) {
			fail("The query " + theQuery + " failed: " + ex.getMessage(), ex);
			return;
		}

		if (resource != null) {
			final String markdownSnippet = generateMarkdownSnippet(theResult, outputSnippet);

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
