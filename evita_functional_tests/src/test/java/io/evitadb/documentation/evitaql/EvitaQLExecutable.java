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

package io.evitadb.documentation.evitaql;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.PriceInCurrency;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.documentation.JavaPrettyPrintingVisitor;
import io.evitadb.documentation.UserDocumentationTest.CreateSnippets;
import io.evitadb.documentation.UserDocumentationTest.OutputSnippet;
import io.evitadb.documentation.markdown.Table;
import io.evitadb.documentation.markdown.Table.Builder;
import io.evitadb.driver.EvitaClient;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ReflectionLookup;
import lombok.RequiredArgsConstructor;
import net.steppschuh.markdowngenerator.MarkdownSerializationException;
import net.steppschuh.markdowngenerator.text.code.CodeBlock;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.documentation.UserDocumentationTest.readFile;
import static io.evitadb.documentation.UserDocumentationTest.resolveSiblingWithDifferentExtension;
import static io.evitadb.documentation.evitaql.CustomJsonVisibilityChecker.allow;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
public class EvitaQLExecutable implements Executable, EvitaTestSupport {
	private static final String REF_LINK = "\uD83D\uDD17 ";
	private static final String ATTR_LINK = ": ";
	public static final String PRICE_LINK = "\uD83E\uDE99 ";
	private static final String PRICE_FOR_SALE = PRICE_LINK + "Price for sale";

	/**
	 * Mandatory header column with entity primary key.
	 */
	private static final String ENTITY_PRIMARY_KEY = "entityPrimaryKey";
	/**
	 * Object mapper used to serialize unknown objects to JSON output.
	 */
	private final static ObjectMapper OBJECT_MAPPER;
	/**
	 * Pretty printer used to format JSON output.
	 */
	private final static DefaultPrettyPrinter DEFAULT_PRETTY_PRINTER;
	/**
	 * Contents of the Java code template that is used to generate Java examples from EvitaQL queries.
	 */
	private final static List<String> JAVA_CODE_TEMPLATE;
	/**
	 * Regex pattern for replacing a placeholder in the Java template.
	 */
	private static final Pattern THE_QUERY_REPLACEMENT = Pattern.compile("^(\\s*)#QUERY#.*$", Pattern.DOTALL);
	/**
	 * Object used for reflection access when `sourceVariable` is evaluated.
	 */
	private static final ReflectionLookup REFLECTION_LOOKUP = new ReflectionLookup(ReflectionCachingBehaviour.CACHE);
	/**
	 * Regex pattern for parsing the reference link from the attribute value.
	 */
	private static final Pattern ATTR_LINK_PARSER = Pattern.compile(ATTR_LINK);

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
				allow(LevelInfo.class)
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

		try (final InputStream is = EvitaQLExecutable.class.getClassLoader().getResourceAsStream("META-INF/documentation/evitaql.java");) {
			JAVA_CODE_TEMPLATE = IOUtils.readLines(is, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Provides access to the {@link EvitaTestContext} instance.
	 */
	private final @Nonnull Supplier<EvitaTestContext> testContextAccessor;
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
	 * Generates the Java code snippet for the given query.
	 */
	@Nonnull
	private static String generateJavaSnippet(@Nonnull Query theQuery) {
		return JAVA_CODE_TEMPLATE
			.stream()
			.map(theLine -> {
				final Matcher replacementMatcher = THE_QUERY_REPLACEMENT.matcher(theLine);
				if (replacementMatcher.matches()) {
					return JavaPrettyPrintingVisitor.toString(theQuery, "\t", replacementMatcher.group(1));
				} else {
					return theLine;
				}
			})
			.collect(Collectors.joining("\n"));
	}

	/**
	 * Generates the GraphQL code snippet for the given query.
	 */
	@Nonnull
	private String generateGraphQLSnippet(@Nonnull Query theQuery) {
		return testContextAccessor.get().getGraphQLQueryConverter().convert(theQuery);
	}

	/**
	 * Generates the GraphQL code snippet for the given query.
	 */
	@Nonnull
	private String generateRestSnippet(@Nonnull Query theQuery) {
		return testContextAccessor.get().getRestQueryConverter().convert(theQuery);
	}

	/**
	 * Generates the MarkDown output with the results of the given query.
	 */
	@Nonnull
	private static String generateMarkdownSnippet(
		@Nonnull EvitaSessionContract session,
		@Nonnull Query query,
		@Nonnull EvitaResponse<SealedEntity> response,
		@Nullable OutputSnippet outputSnippet
	) {
		final String outputFormat = ofNullable(outputSnippet).map(OutputSnippet::forFormat).orElse("md");
		if (outputFormat.equals("md")) {
			return generateMarkDownTable(session, query, response);
		} else if (outputFormat.equals("json")) {
			final String sourceVariable = outputSnippet.sourceVariable();
			return generateMarkDownJsonBlock(response, sourceVariable);
		} else {
			throw new UnsupportedOperationException("Unsupported output format: " + outputFormat);
		}
	}

	/**
	 * Generates output table that contains {@link SealedEntity#getPrimaryKey()} and list of attributes.
	 */
	@Nonnull
	private static String generateMarkDownTable(
		@Nonnull EvitaSessionContract session,
		@Nonnull Query query,
		@Nonnull EvitaResponse<SealedEntity> response
	) {
		final Optional<EntityFetch> entityFetch = ofNullable(query.getRequire())
			.flatMap(
				it -> QueryUtils.findConstraints(
						it, EntityFetch.class, SeparateEntityContentRequireContainer.class
					)
					.stream()
					.findFirst()
			);

		// collect headers for the MarkDown table
		final String[] headers = Stream.of(
				Stream.of(ENTITY_PRIMARY_KEY),
				entityFetch
					.map(it -> QueryUtils.findConstraints(
							it, AttributeContent.class, SeparateEntityContentRequireContainer.class
						)
						.stream()
						.map(AttributeContent::getAttributeNames)
						.flatMap(Arrays::stream)
						.distinct())
					.orElse(Stream.empty()),
				entityFetch
					.map(it -> QueryUtils.findConstraints(
							it, ReferenceContent.class, SeparateEntityContentRequireContainer.class
						)
						.stream()
						.flatMap(refCnt -> {
							final SealedEntitySchema schema = session.getEntitySchemaOrThrow(query.getCollection().getEntityType());
							return Arrays.stream(refCnt.getReferenceNames()).filter(Objects::nonNull)
								.map(schema::getReferenceOrThrowException)
								.flatMap(ref -> {
									final AttributeContent attributeContent = QueryUtils.findConstraint(refCnt, AttributeContent.class, SeparateEntityContentRequireContainer.class);
									if (attributeContent != null) {
										return Arrays.stream(attributeContent.getAttributeNames())
											.map(attr -> REF_LINK + ref.getName() + ATTR_LINK + attr);
									} else {
										return Stream.empty();
									}
								});
						})
						.distinct())
					.orElse(Stream.empty()),
				entityFetch
					.map(it -> QueryUtils.findConstraints(
							it, PriceContent.class, SeparateEntityContentRequireContainer.class
						)
						.stream()
						.map(priceCnt -> {
							if (priceCnt.getFetchMode() == PriceContentMode.RESPECTING_FILTER) {
								return PRICE_FOR_SALE;
							} else {
								return null;
							}
						})
						.filter(Objects::nonNull)
					)
					.orElse(Stream.empty())
			)
			.flatMap(Function.identity())
			.toArray(String[]::new);

		// define the table with header line
		Builder tableBuilder = new Builder()
			.withAlignment(Table.ALIGN_LEFT)
			.addRow((Object[]) headers);

		// prepare price formatter
		final EntityLocaleEquals entityLocale = QueryUtils.findConstraint(query.getFilterBy(), EntityLocaleEquals.class);
		final PriceInCurrency currency = QueryUtils.findConstraint(query.getFilterBy(), PriceInCurrency.class);
		final Locale locale = entityLocale == null ? Locale.ENGLISH : entityLocale.getLocale();
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(locale);
		priceFormatter.setCurrency(currency.getCurrency());
		final NumberFormat percentFormatter = NumberFormat.getNumberInstance(locale);

		// add rows
		for (SealedEntity sealedEntity : response.getRecordData()) {
			tableBuilder.addRow(
				(Object[]) Stream.of(
						Stream.of(String.valueOf(sealedEntity.getPrimaryKey())),
						Arrays.stream(headers)
							.filter(it -> !ENTITY_PRIMARY_KEY.equals(it) && !it.startsWith(REF_LINK))
							.map(sealedEntity::getAttributeValue)
							.filter(Optional::isPresent)
							.map(Optional::get)
							.map(AttributeValue::getValue)
							.map(EvitaDataTypes::formatValue),
						Arrays.stream(headers)
							.filter(it -> it.startsWith(REF_LINK))
							.map(it -> {
								final String[] refAttr = ATTR_LINK_PARSER.split(it.substring(REF_LINK.length()));
								return sealedEntity.getReferences(refAttr[0])
									.stream()
									.filter(ref -> ref.getAttributeValue(refAttr[1]).isPresent())
									.map(ref -> REF_LINK + ref.getReferenceKey().primaryKey() + ATTR_LINK + EvitaDataTypes.formatValue(ref.getAttribute(refAttr[1])))
									.collect(Collectors.joining(", "));
							}),
						Arrays.stream(headers)
							.filter(PRICE_FOR_SALE::equals)
							.map(it -> sealedEntity.getPriceForSale()
								.map(price -> PRICE_LINK + priceFormatter.format(price.getPriceWithTax()) + " (with " + percentFormatter.format(price.getTaxRate()) + "% tax) / " + priceFormatter.format(price.getPriceWithoutTax()))
								.orElse("N/A"))
					)
					.flatMap(Function.identity())
					.toArray(String[]::new)
			);
		}

		// generate MarkDown
		final PaginatedList<SealedEntity> recordPage = (PaginatedList<SealedEntity>) response.getRecordPage();
		return tableBuilder.build().serialize() + "\n\n###### **Page** " + recordPage.getPageNumber() + "/" + recordPage.getLastPageNumber() + " **(Total number of results: "  + response.getTotalRecordCount() + ")**";
	}

	/**
	 * Transforms content that matches the sourceVariable into a JSON and puts it into the MarkDown code block.
	 */
	@Nonnull
	private static String generateMarkDownJsonBlock(
		@Nonnull EvitaResponse<SealedEntity> response,
		@Nullable String sourceVariable
	) {
		final Object theValue;
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
	private static Object extractValueFrom(@Nonnull Object theObject, @Nonnull String[] sourceVariableParts) {
		if (theObject instanceof Map<?, ?> map) {
			for (Entry<?, ?> entry : map.entrySet()) {
				final Object key = entry.getKey();
				final String keyAsString;
				if (key instanceof Class<?> klass) {
					keyAsString = klass.getSimpleName();
				} else {
					keyAsString = String.valueOf(key);
				}
				if (sourceVariableParts[0].equals(keyAsString)) {
					if (sourceVariableParts.length > 1) {
						return extractValueFrom(
							entry.getValue(),
							Arrays.copyOfRange(sourceVariableParts, 1, sourceVariableParts.length)
						);
					} else {
						return entry.getValue();
					}
				}
			}
			return null;
		} else if (theObject instanceof List<?> list) {
			try {
				int index = Integer.parseInt(sourceVariableParts[0]);
				final Object theValue = list.get(index);
				if (theValue == null) {
					return null;
				} else if (sourceVariableParts.length > 1) {
					return extractValueFrom(
						theValue,
						Arrays.copyOfRange(sourceVariableParts, 1, sourceVariableParts.length)
					);
				} else {
					return theValue;
				}
			} catch (Exception e) {
				fail(e);
				return null;
			}
		} else {
			final Method getter = REFLECTION_LOOKUP.findGetter(theObject.getClass(), sourceVariableParts[0]);
			assertNotNull(getter, "Cannot find getter for " + sourceVariableParts[0] + " on `" + theObject.getClass() + "`");
			try {
				final Object theValue = getter.invoke(theObject);
				if (theValue == null) {
					return null;
				} else if (sourceVariableParts.length > 1) {
					return extractValueFrom(
						theValue,
						Arrays.copyOfRange(sourceVariableParts, 1, sourceVariableParts.length)
					);
				} else {
					return theValue;
				}
			} catch (Exception e) {
				fail(e);
				return null;
			}
		}
	}

	@Override
	public void execute() throws Throwable {
		final Query theQuery;
		try {
			theQuery = DefaultQueryParser.getInstance().parseQueryUnsafe(sourceContent);
		} catch (Exception ex) {
			fail(
				"Failed to parse query " +
					ofNullable(resource).map(it -> "from resource " + it).orElse("") + ": \n" +
					sourceContent,
				ex
			);
			return;
		}

		final EvitaContract evitaContract = testContextAccessor.get().getEvitaContract();
		final EvitaResponse<SealedEntity> theResult = evitaContract.queryCatalog(
			"evita",
			session -> {
				try {
					final EvitaResponse<SealedEntity> result = session.querySealedEntity(theQuery);
					assertNotNull(result, "Result for query " + theQuery + " must not be null!");
					return result;
				} catch (Exception ex) {
					fail("The query " + theQuery + " failed: " + ex.getMessage(), ex);
					return null;
				}
			}
		);

		if (resource != null) {
			final String markdownSnippet = evitaContract.queryCatalog(
				"evita",
				session -> {
					return generateMarkdownSnippet(session, theQuery, theResult, outputSnippet);
				}
			);

			if (Arrays.stream(createSnippets).anyMatch(it -> it == CreateSnippets.JAVA)) {
				final String javaSnippet = generateJavaSnippet(theQuery);
				writeFile(resource, "java", javaSnippet);
			}
			if (Arrays.stream(createSnippets).anyMatch(it -> it == CreateSnippets.GRAPHQL)) {
				final String graphQLSnippet = generateGraphQLSnippet(theQuery);
				writeFile(resource, "graphql", graphQLSnippet);
			}
			if (Arrays.stream(createSnippets).anyMatch(it -> it == CreateSnippets.REST)) {
				final String restSnippet = generateRestSnippet(theQuery);
				writeFile(resource, "rest", restSnippet);
			}
			// generate Markdown snippet from the result if required
			final String outputFormat = ofNullable(outputSnippet).map(OutputSnippet::forFormat).orElse("md");
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
