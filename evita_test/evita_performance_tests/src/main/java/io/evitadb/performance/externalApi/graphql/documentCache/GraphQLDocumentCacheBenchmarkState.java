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

package io.evitadb.performance.externalApi.graphql.documentCache;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import io.evitadb.api.CatalogContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.performance.externalApi.graphql.artificial.GraphQLArtificialBenchmarkState;
import io.evitadb.performance.generators.TestDatasetGenerator;
import io.evitadb.performance.setup.EvitaCatalogReusableSetup;
import io.evitadb.utils.Assert;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * State of {@link GraphQLDocumentCacheBenchmark}: an artificial catalog and an in-process catalog data API
 * {@link GraphQL} instance built exactly as the server builds it, with or without the parsed document cache.
 *
 * The measured document is static (as the documents of a real storefront are - fragments inlined at build time,
 * values passed as variables), so with the cache enabled it is lexed, parsed and validated only once.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class GraphQLDocumentCacheBenchmarkState extends GraphQLArtificialBenchmarkState
	implements EvitaCatalogReusableSetup, TestDatasetGenerator {

	/**
	 * Number of products stored in the database.
	 */
	public static final int PRODUCT_COUNT = 1_000;
	/**
	 * Catalog name - dedicated, so that the data set does not collide with the other artificial benchmarks.
	 */
	private static final String CATALOG_NAME = "graphQLDocumentCacheCatalog";
	/**
	 * Selection of a product tile - inlined into every section of the page, as storefront code generators do.
	 */
	private static final String PRODUCT_TILE = """
		primaryKey
		attributes { code url name ean priority validity quantity alias }
		priceForSale { priceWithTax priceWithoutTax taxRate currency priceList }
		brand { referencedEntity { primaryKey attributes { code name } } }
		category {
			attributes { categoryPriority }
			referencedEntity {
				primaryKey
				parentPrimaryKey
				attributes { code name url }
				parents { primaryKey attributes { code name url } }
			}
		}
		""";
	/**
	 * Selection of the product detail.
	 */
	private static final String PRODUCT_DETAIL = PRODUCT_TILE + """
		associatedData { referencedFiles labels }
		prices { priceWithTax priceWithoutTax taxRate currency priceList innerRecordId validity }
		store { referencedEntity { primaryKey attributes { code name } } }
		""";

	/**
	 * Memory budget of the document cache; `0` disables the cache.
	 */
	@Param({"0", "16777216"})
	public long documentCacheSizeInBytes;

	/**
	 * Number of product listing sections on the page; scales the size of the document text.
	 */
	@Param({"1", "20"})
	public int sections;

	/**
	 * In-process catalog data API instance.
	 */
	@Getter private GraphQL graphQL;
	/**
	 * The static document sent by every request.
	 */
	@Getter private String document;

	/**
	 * Creates the data set and the GraphQL instance of the catalog data API.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		final TestDatasetGenerator.ReadyReadEvita readyReadEvita = generateReadTestDataset(
			this.dataGenerator,
			PRODUCT_COUNT,
			this::shouldStartFromScratch,
			this::isCatalogAvailable,
			this::createEvitaInstanceFromExistingData,
			this::createEmptyEvitaInstance,
			getCatalogName(),
			this.generatedEntities,
			SEED,
			this.randomEntityPicker,
			it -> {},
			it -> {},
			this::createEntity,
			it -> it
		);
		this.evita = readyReadEvita.evita();
		this.productSchema = readyReadEvita.productSchema();

		// parallel fetching is off to keep the measurement on the calling thread
		final GraphQLOptions options = new GraphQLOptions(
			null, ":5555", null, null, null, null, false, this.documentCacheSizeInBytes, null
		);
		final CatalogContract catalog = this.evita.getCatalogInstanceOrThrowException(getCatalogName());
		final GraphQLSchema schema = new CatalogDataApiGraphQLSchemaBuilder(options, this.evita, catalog).build();
		this.graphQL = new CatalogGraphQLBuilder(this.evita, catalog, schema, new ObjectMapper()).build(options);
		this.document = createDocument(this.sections);

		// fail fast on an invalid document instead of measuring error responses
		final ExecutionResult result = execute();
		Assert.isPremiseValid(
			result.getErrors().isEmpty(),
			() -> "The benchmark document is invalid: " + result.getErrors()
		);
		System.out.println("\nBenchmark document has " + this.document.length() + " characters.");
	}

	/**
	 * Closes the database.
	 */
	@TearDown(Level.Trial)
	public void closeEvita() {
		this.evita.close();
	}

	@Nonnull
	@Override
	protected String getCatalogName() {
		return CATALOG_NAME;
	}

	/**
	 * Executes the static document with random variables the same way the HTTP handler does.
	 *
	 * @return result of the execution
	 */
	@Nonnull
	public ExecutionResult execute() {
		final ThreadLocalRandom random = ThreadLocalRandom.current();
		final ExecutedEvent event = new ExecutedEvent(GraphQLInstanceType.DATA);
		event.finishInputDeserialization();
		final ExecutionInput executionInput = ExecutionInput.newExecutionInput(this.document)
			.variables(Map.of(
				"pk", random.nextInt(PRODUCT_COUNT) + 1,
				"relatedPks", List.of(
					random.nextInt(PRODUCT_COUNT) + 1,
					random.nextInt(PRODUCT_COUNT) + 1,
					random.nextInt(PRODUCT_COUNT) + 1,
					random.nextInt(PRODUCT_COUNT) + 1
				)
			))
			.graphQLContext(Map.of(GraphQLContextKey.METRIC_EXECUTED_EVENT, event))
			.build();
		final ExecutionResult result = this.graphQL.execute(executionInput);
		event.finish();
		return result;
	}

	/**
	 * Creates a product detail page document with the given number of product listing sections.
	 */
	@Nonnull
	private static String createDocument(int sections) {
		final String priceArguments = "priceInCurrency: CZK, priceInPriceLists: [\"vip\", \"basic\", \"reference\"]";
		final StringBuilder document = new StringBuilder(4096 + sections * 1024);
		document.append("query productDetailPage($pk: Int!, $relatedPks: [Int!]!) {\n")
			.append("product: getProduct(primaryKey: $pk, locale: cs_CZ, ").append(priceArguments).append(") {\n")
			.append(PRODUCT_DETAIL)
			.append("}\n");
		for (int i = 0; i < sections; i++) {
			document.append("section").append(i)
				.append(": listProduct(filterBy: { entityPrimaryKeyInSet: $relatedPks, entityLocaleEquals: cs_CZ, ")
				.append("priceInCurrency: CZK, priceInPriceLists: [\"vip\", \"basic\", \"reference\"] }, limit: 4) {\n")
				.append(PRODUCT_TILE)
				.append("}\n");
		}
		document.append("}\n");
		return document.toString();
	}
}
