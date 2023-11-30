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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.PriceInCurrency;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.filter.PriceValidIn;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.DataInLocales;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.PrettyPrintable;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.documentation.JavaPrettyPrintingVisitor;
import io.evitadb.documentation.JsonExecutable;
import io.evitadb.documentation.UserDocumentationTest.CreateSnippets;
import io.evitadb.documentation.UserDocumentationTest.OutputSnippet;
import io.evitadb.documentation.csharp.CsharpPrettyPrintingVisitor;
import io.evitadb.documentation.markdown.CustomCodeBlock;
import io.evitadb.documentation.markdown.Table;
import io.evitadb.documentation.markdown.Table.Builder;
import io.evitadb.driver.EvitaClient;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ReflectionLookup;
import lombok.RequiredArgsConstructor;
import net.steppschuh.markdowngenerator.MarkdownSerializationException;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.documentation.UserDocumentationTest.readFile;
import static io.evitadb.documentation.UserDocumentationTest.resolveSiblingWithDifferentExtension;
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
public class EvitaQLExecutable extends JsonExecutable implements Executable, EvitaTestSupport {
	public static final String PRICE_LINK = "\uD83E\uDE99 ";
	private static final String REF_LINK = "\uD83D\uDD17 ";
	private static final String REF_ENTITY_LINK = "\uD83D\uDCC4 ";
	private static final String PREDECESSOR_HEAD_SYMBOL = "⎆";
	private static final String PREDECESSOR_SYMBOL = "↻ ";
	private static final String ATTR_LINK = ": ";
	private static final Map<Locale, String> LOCALES = Map.of(
		new Locale("cs"), "\uD83C\uDDE8\uD83C\uDDFF",
		Locale.ENGLISH, "\uD83C\uDDEC\uD83C\uDDE7",
		Locale.GERMAN, "\uD83C\uDDE9\uD83C\uDDEA"
	);
	private static final String PRICE_FOR_SALE = PRICE_LINK + "Price for sale";
	private static final String PRICES = PRICE_LINK + "Prices found";

	/**
	 * Mandatory header column with entity primary key.
	 */
	private static final String ENTITY_PRIMARY_KEY = "entityPrimaryKey";
	/**
	 * Contents of the Java code template that is used to generate Java examples from EvitaQL queries.
	 */
	private final static List<String> JAVA_CODE_TEMPLATE;
	/**
	 * Contents of the Java code template that is used to generate C# examples from EvitaQL queries.
	 */
	private final static List<String> CSHARP_CODE_TEMPLATE;
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
		try (final InputStream is = EvitaQLExecutable.class.getClassLoader().getResourceAsStream("META-INF/documentation/evitaql.java");) {
			JAVA_CODE_TEMPLATE = IOUtils.readLines(is, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}

		try (final InputStream is = EvitaQLExecutable.class.getClassLoader().getResourceAsStream("META-INF/documentation/evitaql.cs");) {
			CSHARP_CODE_TEMPLATE = IOUtils.readLines(is, StandardCharsets.UTF_8);
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
	 * Generates the Java code snippet for the given query.
	 */
	@Nonnull
	private static String generateCsharpSnippet(@Nonnull Query theQuery) {
		return CSHARP_CODE_TEMPLATE
			.stream()
			.map(theLine -> {
				final Matcher replacementMatcher = THE_QUERY_REPLACEMENT.matcher(theLine);
				if (replacementMatcher.matches()) {
					return CsharpPrettyPrintingVisitor.toString(theQuery, "\t", replacementMatcher.group(1));
				} else {
					return theLine;
				}
			})
			.collect(Collectors.joining("\n"));
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
		} else if (outputFormat.equals("string")) {
			final String sourceVariable = ofNullable(outputSnippet).map(OutputSnippet::sourceVariable).orElse(null);
			return generateMarkDownStringBlock(response, sourceVariable);
		} else if (outputFormat.equals("json")) {
			final String sourceVariable = ofNullable(outputSnippet).map(OutputSnippet::sourceVariable).orElse(null);
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
		boolean localizedQuery = ofNullable(query.getFilterBy())
			.map(filterBy -> QueryUtils.findConstraint(filterBy, EntityLocaleEquals.class))
			.isPresent() ||
			ofNullable(query.getRequire())
				.map(require -> QueryUtils.findConstraint(require, DataInLocales.class))
				.isPresent();

		boolean allPriceForSaleConstraintsSet = ofNullable(query.getFilterBy())
			.map(filterBy -> QueryUtils.findConstraint(filterBy, PriceInPriceLists.class))
			.isPresent() &&
			ofNullable(query.getFilterBy())
				.map(filterBy -> QueryUtils.findConstraint(filterBy, PriceInCurrency.class))
				.isPresent() &&
			ofNullable(query.getFilterBy())
				.map(filterBy -> QueryUtils.findConstraint(filterBy, PriceValidIn.class))
				.isPresent();

		// collect headers for the MarkDown table
		final String entityType = query.getCollection().getEntityType();
		final SealedEntitySchema entitySchema = session.getEntitySchemaOrThrow(entityType);
		final String[] headers = Stream.of(
				Stream.of(ENTITY_PRIMARY_KEY),
				entityFetch
					.map(it -> getEntityHeaderStream(
							it, () -> response.getRecordData().stream(),
							entitySchema, localizedQuery, null
						)
					)
					.orElse(Stream.empty()),
				entityFetch
					.map(it -> QueryUtils.findConstraints(
							it, ReferenceContent.class, SeparateEntityContentRequireContainer.class
						)
						.stream()
						.flatMap(refCnt -> Arrays.stream(refCnt.getReferenceNames())
							.filter(Objects::nonNull)
							.map(entitySchema::getReferenceOrThrowException)
							.flatMap(referenceSchema -> {
								final AttributeContent attributeContent = QueryUtils.findConstraint(refCnt, AttributeContent.class, SeparateEntityContentRequireContainer.class);
								if (attributeContent != null) {
									final Stream<String> attributeNames;
									if (attributeContent.isAllRequested()) {
										final Stream<AttributeSchemaContract> attributes = referenceSchema.getAttributes().values().stream();
										attributeNames = (localizedQuery ? attributes.filter(AttributeSchemaContract::isLocalized) : attributes)
											.map(AttributeSchemaContract::getName)
											.filter(
												attrName -> response.getRecordData().stream()
													.flatMap(entity -> entity.getReferences(referenceSchema.getName()).stream())
													.anyMatch(reference -> reference.getAttributeValue(attrName).isPresent())
											);
									} else {
										attributeNames = Arrays.stream(attributeContent.getAttributeNames());
									}
									return Stream.concat(
										attributeNames
											.flatMap(
												attributeName -> transformLocalizedAttributes(
													() -> response.getRecordData().stream(), attributeName, entitySchema.getLocales(), referenceSchema,
													entity -> entity.getReferences(referenceSchema.getName()).stream().map(AttributesContract.class::cast),
													REF_LINK + referenceSchema.getName() + ATTR_LINK
												)
											),
										getReferencedEntityHeaders(response, refCnt, referenceSchema, entitySchema, localizedQuery)
									);
								} else {
									return getReferencedEntityHeaders(response, refCnt, referenceSchema, entitySchema, localizedQuery);
								}
							}))
						.distinct())
					.orElse(Stream.empty()),
				entityFetch
					.map(it -> QueryUtils.findConstraints(
								it, PriceContent.class, SeparateEntityContentRequireContainer.class
							)
							.stream()
							.flatMap(priceCnt -> {
								if (priceCnt.getFetchMode() == PriceContentMode.RESPECTING_FILTER) {
									return allPriceForSaleConstraintsSet ?
										Stream.of(PRICE_FOR_SALE) : Stream.of(PRICE_FOR_SALE, PRICES);
								} else {
									return Stream.empty();
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
		final Locale locale = Optional.ofNullable(query.getFilterBy())
			.map(f -> QueryUtils.findConstraint(f, EntityLocaleEquals.class))
			.map(EntityLocaleEquals::getLocale)
			.orElse(Locale.ENGLISH);

		final Currency currency = Optional.ofNullable(query.getFilterBy())
			.map(f -> QueryUtils.findConstraint(f, PriceInCurrency.class))
			.map(PriceInCurrency::getCurrency)
			.orElse(Currency.getInstance("EUR"));
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(locale);
		priceFormatter.setCurrency(currency);
		final NumberFormat percentFormatter = NumberFormat.getNumberInstance(locale);

		// add rows
		for (SealedEntity sealedEntity : response.getRecordData()) {
			tableBuilder.addRow(
				(Object[]) Stream.of(
						Stream.of(String.valueOf(sealedEntity.getPrimaryKey())),
						Arrays.stream(headers)
							.filter(it -> !ENTITY_PRIMARY_KEY.equals(it) && !it.startsWith(REF_LINK) && !it.startsWith(PRICE_LINK))
							.map(EvitaQLExecutable::toAttributeKey)
							.map(sealedEntity::getAttributeValue)
							.map(
								it -> it.map(AttributeValue::value)
									.map(EvitaQLExecutable::formatValue)
									.orElse(null)
							),
						Arrays.stream(headers)
							.filter(it -> it.startsWith(REF_LINK))
							.map(it -> {
								final String[] refAttr = ATTR_LINK_PARSER.split(it.substring(REF_LINK.length()));
								if (refAttr.length == 1) {
									// REF_LINK + " " + referenceSchema.getName() + " " + REF_ENTITY_LINK + referenceSchema.getReferencedEntityType()
									final String[] refEntitySplit = refAttr[0].split(REF_ENTITY_LINK);
									final String refName = refEntitySplit[0].trim();
									return sealedEntity.getReferences(refName)
										.stream()
										.map(ReferenceContract::getReferencedPrimaryKey)
										.map(refEntity -> REF_ENTITY_LINK + refEntitySplit[1] + ATTR_LINK + refEntity)
										.collect(Collectors.joining(", "));
								} else {
									final AttributeKey attributeKey = toAttributeKey(refAttr[1]);
									if (refAttr[0].contains(REF_ENTITY_LINK)) {
										final String[] refEntitySplit = refAttr[0].split(REF_ENTITY_LINK);
										final String refName = refEntitySplit[0].trim();
										return sealedEntity.getReferences(refName)
											.stream()
											.map(ReferenceContract::getReferencedEntity)
											.filter(Optional::isPresent)
											.map(Optional::get)
											.filter(refEntity -> refEntity.getAttributeValue(attributeKey).isPresent())
											.map(refEntity -> {
												final String formattedValue = formatValue(refEntity.getAttributeValue(attributeKey).get().value());
												return REF_ENTITY_LINK + refEntitySplit[1] + " " + refEntity.getPrimaryKey() + ATTR_LINK + formattedValue;
											})
											.collect(Collectors.joining(", "));
									} else {
										return sealedEntity.getReferences(refAttr[0])
											.stream()
											.filter(ref -> ref.getAttributeValue(attributeKey).isPresent())
											.map(ref -> {
												final String formattedValue = formatValue(ref.getAttributeValue(attributeKey).get().value());
												return REF_LINK + ref.getReferenceKey().primaryKey() + ATTR_LINK + formattedValue;
											})
											.collect(Collectors.joining(", "));
									}
								}
							}),
						Arrays.stream(headers)
							.filter(PRICE_FOR_SALE::equals)
							.map(it -> sealedEntity.getPriceForSaleIfAvailable()
								.map(price -> PRICE_LINK + priceFormatter.format(price.priceWithTax()) + " (with " + percentFormatter.format(price.taxRate()) + "% tax) / " + priceFormatter.format(price.priceWithoutTax()))
								.orElse("N/A")),
						Arrays.stream(headers)
							.filter(PRICES::equals)
							.map(it -> {
								final Collection<PriceContract> prices = sealedEntity.getPrices();
								if (prices.isEmpty()) {
									return "N/A";
								} else {
									return prices
										.stream()
										.limit(3)
										.map(price -> PRICE_LINK + priceFormatter.format(price.priceWithTax()))
										.collect(Collectors.joining(", ")) +
										(prices.size() > 3 ? " ... and " + (prices.size() - 3) + " other prices" : "");
								}
							})
					)
					.flatMap(Function.identity())
					.toArray(String[]::new)
			);
		}

		// generate MarkDown
		final PaginatedList<SealedEntity> recordPage = (PaginatedList<SealedEntity>) response.getRecordPage();
		return tableBuilder.build().serialize() + "\n\n###### **Page** " + recordPage.getPageNumber() + "/" + recordPage.getLastPageNumber() + " **(Total number of results: " + response.getTotalRecordCount() + ")**";
	}

	/**
	 * Returns stream of attribute headers for particular entity fetch of reference content.
	 *
	 * @param response         original response with the data
	 * @param referenceContent reference content constraint
	 * @param referenceSchema  reference schema
	 * @param entitySchema     entity schema
	 * @param localizedQuery   true if the query is localized
	 * @return stream of attribute headers
	 */
	@Nonnull
	private static Stream<String> getReferencedEntityHeaders(
		@Nonnull EvitaResponse<SealedEntity> response,
		@Nonnull ReferenceContent referenceContent,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull SealedEntitySchema entitySchema,
		boolean localizedQuery
	) {
		return Stream.concat(
			Stream.of(REF_LINK + " " + referenceSchema.getName() + " " + REF_ENTITY_LINK + referenceSchema.getReferencedEntityType()),
			QueryUtils.findConstraints(
					referenceContent, EntityFetch.class, SeparateEntityContentRequireContainer.class
				)
				.stream()
				.flatMap(
					refEntity -> getEntityHeaderStream(
						refEntity,
						() -> response.getRecordData()
							.stream()
							.flatMap(theEntity -> theEntity.getReferences(referenceSchema.getName()).stream())
							.map(theRef -> theRef.getReferencedEntity().orElse(null))
							.filter(Objects::nonNull),
						entitySchema, localizedQuery,
						REF_LINK + " " + referenceSchema.getName() + " " + REF_ENTITY_LINK + referenceSchema.getReferencedEntityType() + ATTR_LINK
					)
				)
		);
	}

	/**
	 * Returns stream of attribute headers for particular entity fetch.
	 *
	 * @param entityFetch          the entity fetch constraint
	 * @param entityStreamAccessor provider of the entity bodies
	 * @param entitySchema         the targeted entity schema
	 * @param localizedQuery       true if the query is localized
	 * @param prefix               prefix for the attribute header
	 */
	@Nonnull
	private static Stream<String> getEntityHeaderStream(
		@Nonnull EntityFetch entityFetch,
		@Nonnull Supplier<Stream<SealedEntity>> entityStreamAccessor,
		@Nonnull SealedEntitySchema entitySchema,
		boolean localizedQuery,
		@Nullable String prefix
	) {
		return QueryUtils.findConstraints(
				entityFetch, AttributeContent.class, SeparateEntityContentRequireContainer.class
			)
			.stream()
			.flatMap(attributeContent -> {
				if (attributeContent.isAllRequested()) {
					final Stream<EntityAttributeSchemaContract> attributes = entitySchema.getAttributes().values().stream();
					return (localizedQuery ? attributes.filter(AttributeSchemaContract::isLocalized) : attributes)
						.map(AttributeSchemaContract::getName)
						.filter(attrName -> entityStreamAccessor.get().anyMatch(entity -> entity.getAttributeValue(attrName).isPresent()));
				} else {
					return Arrays.stream(attributeContent.getAttributeNames());
				}
			})
			.flatMap(
				attributeName -> transformLocalizedAttributes(
					entityStreamAccessor, attributeName, entitySchema.getLocales(), entitySchema, Stream::of,
					prefix
				)
			)
			.distinct();
	}

	/**
	 * Formats the value for the MarkDown table.
	 *
	 * @param value value to be formatted
	 * @return formatted value
	 */
	@Nonnull
	private static String formatValue(@Nonnull Serializable value) {
		if (value instanceof Predecessor predecessor) {
			return predecessor.isHead() ? PREDECESSOR_HEAD_SYMBOL : PREDECESSOR_SYMBOL + predecessor.predecessorId();
		} else {
			return EvitaDataTypes.formatValue(value);
		}
	}

	@Nonnull
	private static AttributeKey toAttributeKey(@Nonnull String attributeHeader) {
		if (attributeHeader.startsWith("\uD83C")) {
			for (Entry<Locale, String> entry : LOCALES.entrySet()) {
				if (attributeHeader.startsWith(entry.getValue())) {
					return new AttributeKey(
						attributeHeader.substring(entry.getValue().length() + 1),
						entry.getKey()
					);
				}
			}
			throw new IllegalStateException("Unknown locale for attribute header: " + attributeHeader);
		} else {
			return new AttributeKey(attributeHeader);
		}
	}

	@Nonnull
	private static Stream<String> transformLocalizedAttributes(
		@Nonnull Supplier<Stream<SealedEntity>> entityStreamAccessor,
		@Nonnull String attributeName,
		@Nonnull Set<Locale> entityLocales,
		@Nonnull AttributeSchemaProvider<?> schema,
		@Nonnull Function<SealedEntity, Stream<AttributesContract>> attributesProvider,
		@Nullable String prefix
	) {
		final boolean localized = schema
			.getAttribute(attributeName)
			.orElseThrow()
			.isLocalized();
		if (localized) {
			return entityLocales.stream()
				.filter(locale -> entityStreamAccessor
					.get()
					.flatMap(attributesProvider)
					.anyMatch(attributeProvider -> attributeProvider.attributesAvailable(locale) &&
						attributeProvider.getAttributeValue(attributeName, locale).isPresent())
				)
				.map(locale -> ofNullable(LOCALES.get(locale)).orElseThrow(() -> new IllegalArgumentException("No flag for locale: " + locale)) + " " + attributeName)
				.map(it -> ofNullable(prefix)
					.map(it::concat)
					.orElse(it)
				);
		} else {
			return Stream.of(
				ofNullable(prefix)
					.map(it -> it + attributeName)
					.orElse(attributeName)
			);
		}
	}

	/**
	 * Transforms content that matches the sourceVariable into a String and puts it into the MarkDown code block.
	 */
	@Nonnull
	private static String generateMarkDownStringBlock(
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
			.map(it -> it instanceof PrettyPrintable pp ? pp.prettyPrint() : it.toString())
			.orElse("");

		try {
			return new CustomCodeBlock(json, "md").serialize();
		} catch (MarkdownSerializationException e) {
			fail(e);
			return "";
		}
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
			final List<String> markdownSnippets = evitaContract.queryCatalog(
				"evita",
				session -> {
					return outputSnippet.stream()
						.map(snippet -> generateMarkdownSnippet(session, theQuery, theResult, snippet))
						.toList();
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
			if (Arrays.stream(createSnippets).anyMatch(it -> it == CreateSnippets.CSHARP)) {
				final String csharpSnippet = generateCsharpSnippet(theQuery);
				writeFile(resource, "cs", csharpSnippet);
			}
			// generate Markdown snippet from the result if required
			for (int i = 0; i < outputSnippet.size(); i++) {
				final OutputSnippet snippet = outputSnippet.get(i);
				final String markdownSnippet = markdownSnippets.get(i);
				final String outputFormat = ofNullable(snippet).map(OutputSnippet::forFormat).orElse("md");
				if (Arrays.stream(createSnippets).anyMatch(it -> it == CreateSnippets.MARKDOWN)) {
					if (snippet == null) {
						writeFile(resource, outputFormat, markdownSnippet);
					} else {
						writeFile(snippet.path(), markdownSnippet);
					}
				}

				// assert MarkDown file contents
				final Optional<String> markDownFile = snippet == null ?
					readFile(resource, outputFormat) : readFile(snippet.path());
				markDownFile.ifPresent(
					content -> {
						assertEquals(
							content,
							markdownSnippet
						);

						final Path assertSource = snippet == null ?
							resolveSiblingWithDifferentExtension(resource, outputFormat).normalize() :
							snippet.path().normalize();

						final String relativePath = assertSource.toString().substring(rootDirectory.normalize().toString().length());
						System.out.println("Markdown snippet `" + relativePath + "` contents verified OK. \uD83D\uDE0A");
					}
				);
			}
		}
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

}
