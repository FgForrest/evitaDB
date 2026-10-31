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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.SchemaPostProcessor;
import io.evitadb.api.SchemaPostProcessorCapturingResult;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.Expression;
import io.evitadb.api.requestResponse.data.annotation.Histogram;
import io.evitadb.api.requestResponse.data.annotation.PriceForSale;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.annotation.ScopeAttributeSettings;
import io.evitadb.api.requestResponse.data.annotation.ScopeReferenceSettings;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that re-defining an unchanged entity schema from its annotated model class after an application restart
 * emits no schema mutation at all.
 *
 * Applications reconcile their evitaDB schema on every start by running {@link ClassSchemaAnalyzer} over
 * their annotated model interfaces, optionally followed by a {@link SchemaPostProcessor} that adjusts what the
 * annotations cannot express. When the declared schema did not change, that reconciliation must be a pure no-op:
 * every emitted mutation is written to the transaction's WAL and carries conflict keys - a global attribute mutation
 * even takes a catalog-wide one - so a phantom mutation makes the start-up transaction collide with any concurrent
 * writer.
 *
 * Each test models one isolated schema shape, defines it in one session, restarts the embedded engine so the schema
 * is read back from storage, and re-analyzes the same model in a fresh session. The gRPC transport is covered
 * separately by `EvitaClientSchemaRedefinitionTest`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Re-defining an unchanged schema from a model class after restart")
@Tag(CONTRACT)
@Tag(SCHEMA)
class ClassSchemaAnalyzerRedefinitionTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_URL = "url";
	private static final String ATTRIBUTE_URL_INACTIVE = "urlInactive";
	private static final String ATTRIBUTE_DISPLAY_MODE = "displayMode";
	private static final String ATTRIBUTE_RANGED = "ranged";
	private static final String ATTRIBUTE_NUMERIC_VALUE = "numericValue";
	private static final String ATTRIBUTE_NUMERIC_VALUE_TO = "numericValueTo";
	private static final String ATTRIBUTE_VARIANT = "variant";
	private static final String REFERENCE_FEATURE_VALUES = "featureValues";
	private static final String ENTITY_FEATURE = "RedefinedFeature";
	private static final String ENTITY_FEATURE_VALUE = "RedefinedFeatureValue";
	private static final String ENTITY_MULTI_CURRENCY_PRODUCT = "RedefinedMultiCurrencyProduct";
	private static final String CURRENCY_EUR = "EUR";
	private static final String CURRENCY_CZK = "CZK";
	private static final String HISTOGRAM_SINGLE_VALUE = "singleValueHistogram";
	private static final String HISTOGRAM_RANGE_VALUE = "rangeValueHistogram";
	private static final String EXPRESSION_FACETED_PARTIALLY =
		"$reference.groupEntity?.attributes['displayMode'] == 'LIST'";
	private static final String EXPRESSION_BUCKETED_PARTIALLY =
		"$reference.groupEntity?.attributes['displayMode'] == 'SLIDER'";
	private static final String EXPRESSION_NOT_RANGE =
		"$reference.groupEntity?.attributes['ranged'] == false";
	private static final String EXPRESSION_RANGE =
		"$reference.groupEntity?.attributes['ranged'] == true";
	private static final String EXPRESSION_BASIC_UNIT_VALUE =
		"$reference.referencedEntity?.attributes['numericValue'] ?? 0.0";
	private static final String EXPRESSION_RANGE_BASIC_UNIT_VALUE =
		"$reference.referencedEntity?.attributes['numericValueTo']";
	/**
	 * Indexed price places the post-processor enforces in place of the `@PriceForSale` default of `2`.
	 */
	private static final int CONFIGURED_PRICE_PLACES = 3;

	private TestPaths paths;
	private Evita evita;

	/**
	 * Post-processor that re-declares nothing - used where the model class alone defines the whole schema.
	 */
	private static final SchemaPostProcessor NO_POST_PROCESSING = (catalogBuilder, entityBuilder) -> {
		// the model class alone defines the schema
	};

	/**
	 * Post-processor relaxing the global uniqueness of `url` / `urlInactive` from the annotated
	 * `UNIQUE_WITHIN_CATALOG` to `UNIQUE_WITHIN_CATALOG_LOCALE` in every scope, as an application does when the same URL
	 * is allowed in different locales.
	 */
	private static final SchemaPostProcessor RELAX_URL_UNIQUENESS = (catalogBuilder, entityBuilder) -> catalogBuilder
		.withAttribute(ATTRIBUTE_URL, String.class, it -> it.uniqueGloballyWithinLocaleInScope(Scope.values()))
		.withAttribute(ATTRIBUTE_URL_INACTIVE, String[].class, it -> it.uniqueGloballyWithinLocaleInScope(Scope.values()));

	/**
	 * Post-processor overriding the indexed price places declared by `@PriceForSale` with a configured value, as an
	 * application applying a catalog-specific price precision does.
	 */
	private static final SchemaPostProcessor CONFIGURE_PRICE_PLACES =
		(catalogBuilder, entityBuilder) -> entityBuilder.withPrice(CONFIGURED_PRICE_PLACES);

	/**
	 * Post-processor indexing the price in the archived scope as well, which `@PriceForSale` cannot express.
	 */
	private static final SchemaPostProcessor INDEX_PRICE_IN_BOTH_SCOPES =
		(catalogBuilder, entityBuilder) -> entityBuilder.withPriceIndexedInScope(Scope.LIVE, Scope.ARCHIVED);

	/**
	 * Creates a fresh embedded engine with an empty test catalog.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ClassSchemaAnalyzerRedefinitionTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	/**
	 * Closes the engine and removes its storage.
	 */
	@AfterEach
	void tearDown() throws IOException {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@DisplayName("global representative attribute with per-scope local uniqueness emits no mutation")
	@Test
	void shouldEmitNoMutationWhenRedefiningRepresentativeGlobalAttribute() {
		defineSchemas(NO_POST_PROCESSING, RepresentativeGlobalAttributeModel.class);
		restartEvita();

		final LocalCatalogSchemaMutation[] mutations = redefineAndCapture(
			RepresentativeGlobalAttributeModel.class, NO_POST_PROCESSING
		);

		assertNoMutations(mutations);
	}

	@DisplayName("globally unique attribute without explicit local uniqueness emits no mutation")
	@Test
	void shouldEmitNoMutationWhenRedefiningGloballyUniqueAttribute() {
		defineSchemas(NO_POST_PROCESSING, GloballyUniqueAttributeModel.class);
		restartEvita();

		final LocalCatalogSchemaMutation[] mutations = redefineAndCapture(
			GloballyUniqueAttributeModel.class, NO_POST_PROCESSING
		);

		assertNoMutations(mutations);
	}

	@DisplayName("post-processor restoring the stored global uniqueness emits no mutation")
	@Test
	void shouldEmitNoMutationWhenPostProcessorRestoresStoredGlobalUniqueness() {
		defineSchemas(RELAX_URL_UNIQUENESS, GloballyUniqueAttributeModel.class);
		restartEvita();

		final LocalCatalogSchemaMutation[] mutations = redefineAndCapture(
			GloballyUniqueAttributeModel.class, RELAX_URL_UNIQUENESS
		);

		assertNoMutations(mutations);
	}

	@DisplayName("post-processor restoring the stored indexed price places emits no mutation")
	@Test
	void shouldEmitNoMutationWhenPostProcessorRestoresStoredPricePlaces() {
		defineSchemas(CONFIGURE_PRICE_PLACES, PricedProductModel.class);
		restartEvita();

		final LocalCatalogSchemaMutation[] mutations = redefineAndCapture(
			PricedProductModel.class, CONFIGURE_PRICE_PLACES
		);

		assertNoMutations(mutations);
	}

	@DisplayName("reference with partial faceting, conditional histograms and reference-ref helpers emits no mutation")
	@Test
	void shouldEmitNoMutationWhenRedefiningReferenceWithPartialFacetingAndHistograms() {
		defineSchemas(
			NO_POST_PROCESSING,
			FeatureModel.class, FeatureValueModel.class, FeaturedProductModel.class
		);
		restartEvita();

		final LocalCatalogSchemaMutation[] mutations = redefineAndCapture(
			FeaturedProductModel.class, NO_POST_PROCESSING
		);

		assertNoMutations(mutations);
	}

	@DisplayName("reference with partial faceting and histograms declared without scopes emits no mutation")
	@Test
	void shouldEmitNoMutationWhenRedefiningReferenceWithUnscopedPartialFaceting() {
		defineSchemas(
			NO_POST_PROCESSING,
			FeatureModel.class, FeatureValueModel.class, UnscopedFeaturedProductModel.class
		);
		restartEvita();

		final LocalCatalogSchemaMutation[] mutations = redefineAndCapture(
			UnscopedFeaturedProductModel.class, NO_POST_PROCESSING
		);

		assertNoMutations(mutations);
	}

	@DisplayName("currency added by the model keeps the scopes the stored price is indexed in")
	@Test
	void shouldKeepPriceIndexedScopesWhenModelAddsCurrency() {
		defineSchemas(INDEX_PRICE_IN_BOTH_SCOPES, SingleCurrencyProductModel.class);
		restartEvita();

		defineSchemas(NO_POST_PROCESSING, TwoCurrencyProductModel.class);

		final EntitySchemaContract productSchema = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrowException(ENTITY_MULTI_CURRENCY_PRODUCT);
			}
		);
		assertEquals(
			Set.of(Currency.getInstance(CURRENCY_EUR), Currency.getInstance(CURRENCY_CZK)),
			productSchema.getCurrencies()
		);
		assertEquals(
			EnumSet.of(Scope.LIVE, Scope.ARCHIVED), productSchema.getPriceIndexedInScopes(),
			"Adding a currency must not narrow the scopes the price is indexed in."
		);
	}

	@DisplayName("trait overriding an inherited reference declaration emits no mutation")
	@Test
	void shouldEmitNoMutationWhenTraitOverridesInheritedReference() {
		defineSchemas(
			NO_POST_PROCESSING,
			FeatureModel.class, FeatureValueModel.class, OverridingFeaturedProductModel.class
		);
		restartEvita();

		final LocalCatalogSchemaMutation[] mutations = redefineAndCapture(
			OverridingFeaturedProductModel.class, NO_POST_PROCESSING
		);

		assertNoMutations(mutations);
	}

	/**
	 * Asserts the captured mutations are empty and lists them in the failure message otherwise.
	 *
	 * @param mutations mutations captured from the second analysis
	 */
	private static void assertNoMutations(@Nullable LocalCatalogSchemaMutation[] mutations) {
		assertNotNull(mutations, "The analysis result was not captured.");
		assertEquals(
			0, mutations.length,
			"Re-defining an unchanged schema must emit no mutation, but got " + mutations.length + ":\n" +
				String.join("\n", Arrays.stream(mutations).map(Object::toString).toList())
		);
	}

	/**
	 * Defines entity schemas from the given model classes, in order, within a single session.
	 *
	 * @param postProcessor post-processor applied to every model class
	 * @param modelClasses  model classes to define, referenced entities first
	 */
	private void defineSchemas(@Nonnull SchemaPostProcessor postProcessor, @Nonnull Class<?>... modelClasses) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (Class<?> modelClass : modelClasses) {
					session.defineEntitySchemaFromModelClass(modelClass, postProcessor);
				}
			}
		);
	}

	/**
	 * Closes the engine and starts a new one over the same storage, so the next analysis works with the schema read
	 * back from disk - exactly what an application restart sees.
	 */
	private void restartEvita() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	/**
	 * Re-analyzes the model class in a fresh session and captures the mutations the analysis produced.
	 *
	 * @param modelClass    model class to analyze
	 * @param postProcessor post-processor applied after the annotations
	 * @return mutations the analysis produced
	 */
	@Nullable
	private LocalCatalogSchemaMutation[] redefineAndCapture(
		@Nonnull Class<?> modelClass,
		@Nonnull SchemaPostProcessor postProcessor
	) {
		final AtomicReference<LocalCatalogSchemaMutation[]> captured = new AtomicReference<>();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchemaFromModelClass(
					modelClass,
					new SchemaPostProcessorCapturingResult() {
						@Override
						public void postProcess(
							@Nonnull CatalogSchemaBuilder catalogSchemaBuilder,
							@Nonnull EntitySchemaBuilder entitySchemaBuilder
						) {
							postProcessor.postProcess(catalogSchemaBuilder, entitySchemaBuilder);
						}

						@Override
						public void captureResult(@Nonnull LocalCatalogSchemaMutation[] mutations) {
							captured.set(mutations);
						}
					}
				);
			}
		);
		return captured.get();
	}

	/**
	 * Entity sharing a global, representative `code` attribute that is unique within the collection in both scopes.
	 */
	@Entity(name = "RedefinedBrand")
	public interface RepresentativeGlobalAttributeModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the global, representative code.
		 */
		@Attribute(
			name = ATTRIBUTE_CODE,
			global = true,
			representative = true,
			scope = {
				@ScopeAttributeSettings(
					scope = Scope.LIVE, unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, sortable = true
				),
				@ScopeAttributeSettings(scope = Scope.ARCHIVED, unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		)
		@Nonnull
		String getCode();

	}

	/**
	 * Entity with localized, globally unique `url` / `urlInactive` attributes that declare no local uniqueness.
	 */
	@Entity(name = "RedefinedPage", allowedLocales = "cs")
	public interface GloballyUniqueAttributeModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the localized URL unique across the whole catalog.
		 */
		@Attribute(
			name = ATTRIBUTE_URL,
			localized = true,
			nullable = true,
			global = true,
			scope = {
				@ScopeAttributeSettings(scope = Scope.LIVE, uniqueGlobally = GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG),
				@ScopeAttributeSettings(scope = Scope.ARCHIVED, uniqueGlobally = GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
			}
		)
		@Nullable
		String getUrl();

		/**
		 * Returns the localized former URLs, unique across the whole catalog.
		 */
		@Attribute(
			name = ATTRIBUTE_URL_INACTIVE,
			localized = true,
			nullable = true,
			global = true,
			scope = {
				@ScopeAttributeSettings(scope = Scope.LIVE, uniqueGlobally = GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG),
				@ScopeAttributeSettings(scope = Scope.ARCHIVED, uniqueGlobally = GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
			}
		)
		@Nonnull
		List<String> getUrlInactive();

	}

	/**
	 * Entity with a selling price indexed with the `@PriceForSale` default precision.
	 */
	@Entity(name = "RedefinedPricedProduct")
	public interface PricedProductModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the price for sale.
		 */
		@PriceForSale
		@Nullable
		PriceContract getPriceForSale();

	}

	/**
	 * Entity with a selling price allowed in a single currency.
	 */
	@Entity(name = ENTITY_MULTI_CURRENCY_PRODUCT)
	public interface SingleCurrencyProductModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the price for sale.
		 */
		@PriceForSale(allowedCurrencies = CURRENCY_EUR)
		@Nullable
		PriceContract getPriceForSale();

	}

	/**
	 * The same entity as `SingleCurrencyProductModel`, whose selling price allows one more currency.
	 */
	@Entity(name = ENTITY_MULTI_CURRENCY_PRODUCT)
	public interface TwoCurrencyProductModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the price for sale.
		 */
		@PriceForSale(allowedCurrencies = {CURRENCY_EUR, CURRENCY_CZK})
		@Nullable
		PriceContract getPriceForSale();

	}

	/**
	 * Group entity of the feature values; its attributes drive the partial faceting and histogram expressions.
	 */
	@Entity(name = ENTITY_FEATURE)
	public interface FeatureModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the mode the feature filter is displayed in.
		 */
		@Attribute(name = ATTRIBUTE_DISPLAY_MODE, nullable = true, filterable = true)
		@Nullable
		String getDisplayMode();

		/**
		 * Returns true when the feature values carry a range.
		 */
		@Attribute(name = ATTRIBUTE_RANGED, nullable = true, filterable = true)
		@Nullable
		Boolean getRanged();

	}

	/**
	 * Referenced feature value; its attributes feed the histogram value expressions.
	 */
	@Entity(name = ENTITY_FEATURE_VALUE)
	public interface FeatureValueModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the numeric value.
		 */
		@Attribute(name = ATTRIBUTE_NUMERIC_VALUE, nullable = true, filterable = true, indexedDecimalPlaces = 2)
		@Nullable
		BigDecimal getNumericValue();

		/**
		 * Returns the upper bound of the numeric range.
		 */
		@Attribute(name = ATTRIBUTE_NUMERIC_VALUE_TO, nullable = true, filterable = true, indexedDecimalPlaces = 2)
		@Nullable
		BigDecimal getNumericValueTo();

	}

	/**
	 * Reference DTO pointing to a feature value and its feature group.
	 */
	public interface FeatureValueReference {

		/**
		 * Returns the primary key of the referenced feature value.
		 */
		@ReferencedEntity
		int getFeatureValueId();

		/**
		 * Returns the primary key of the feature group, if any.
		 */
		@ReferencedEntityGroup
		@Nonnull
		Optional<Integer> getFeatureId();

		/**
		 * Returns true when the feature value distinguishes product variants.
		 */
		@Attribute(name = ATTRIBUTE_VARIANT, filterable = true)
		default boolean isVariant() {
			return false;
		}

	}

	/**
	 * Entity referencing feature values with partial faceting, two conditional histograms and partial bucketing in
	 * the live scope, plus the `@ReferenceRef` helper accessors over the same reference.
	 */
	@Entity(name = "RedefinedFeaturedProduct")
	public interface FeaturedProductModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns all feature values assigned to the product.
		 */
		@Reference(
			name = REFERENCE_FEATURE_VALUES,
			entity = ENTITY_FEATURE_VALUE,
			groupEntity = ENTITY_FEATURE,
			scope = {
				@ScopeReferenceSettings(
					scope = Scope.LIVE,
					indexed = ReferenceIndexType.FOR_FILTERING,
					indexedComponents = {
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					},
					facetedPartially = @Expression(EXPRESSION_FACETED_PARTIALLY),
					bucketed = {
						@Histogram(
							nameOfTheIndex = HISTOGRAM_SINGLE_VALUE,
							assignedWhen = @Expression(EXPRESSION_NOT_RANGE),
							value = @Expression(EXPRESSION_BASIC_UNIT_VALUE)
						),
						@Histogram(
							nameOfTheIndex = HISTOGRAM_RANGE_VALUE,
							assignedWhen = @Expression(EXPRESSION_RANGE),
							value = @Expression(EXPRESSION_RANGE_BASIC_UNIT_VALUE)
						)
					},
					bucketedPartially = @Expression(EXPRESSION_BUCKETED_PARTIALLY)
				),
				@ScopeReferenceSettings(scope = Scope.ARCHIVED, indexed = ReferenceIndexType.FOR_FILTERING)
			}
		)
		@Nonnull
		List<FeatureValueReference> getFeatureValues();

		/**
		 * Returns the feature value with the given primary key, if assigned.
		 */
		@ReferenceRef(REFERENCE_FEATURE_VALUES)
		@Nonnull
		Optional<FeatureValueReference> getFeatureValueById(int featureValuePrimaryKey);

		/**
		 * Returns all feature values, or empty when the reference was not fetched.
		 */
		@ReferenceRef(REFERENCE_FEATURE_VALUES)
		@Nonnull
		Optional<List<FeatureValueReference>> getFeatureValuesIfAvailable();

	}

	/**
	 * Entity referencing feature values with partial faceting, a conditional histogram and partial bucketing declared
	 * by the general `@Reference` settings, which apply to the default scope.
	 */
	@Entity(name = "RedefinedUnscopedFeaturedProduct")
	public interface UnscopedFeaturedProductModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns all feature values assigned to the product.
		 */
		@Reference(
			name = REFERENCE_FEATURE_VALUES,
			entity = ENTITY_FEATURE_VALUE,
			groupEntity = ENTITY_FEATURE,
			indexed = ReferenceIndexType.FOR_FILTERING,
			indexedComponents = {
				ReferenceIndexedComponents.REFERENCED_ENTITY,
				ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
			},
			facetedPartially = @Expression(EXPRESSION_FACETED_PARTIALLY),
			bucketed = @Histogram(
				nameOfTheIndex = HISTOGRAM_SINGLE_VALUE,
				assignedWhen = @Expression(EXPRESSION_NOT_RANGE),
				value = @Expression(EXPRESSION_BASIC_UNIT_VALUE)
			),
			bucketedPartially = @Expression(EXPRESSION_BUCKETED_PARTIALLY)
		)
		@Nonnull
		List<FeatureValueReference> getFeatureValues();

	}

	/**
	 * Base trait declaring the feature values reference with a single unconditional histogram.
	 */
	public interface BaseFeatureValuesTrait {

		/**
		 * Returns all feature values assigned to the product.
		 */
		@Reference(
			name = REFERENCE_FEATURE_VALUES,
			entity = ENTITY_FEATURE_VALUE,
			groupEntity = ENTITY_FEATURE,
			scope = {
				@ScopeReferenceSettings(
					scope = Scope.LIVE,
					indexed = ReferenceIndexType.FOR_FILTERING,
					indexedComponents = {
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					},
					facetedPartially = @Expression(EXPRESSION_FACETED_PARTIALLY),
					bucketed = @Histogram(
						nameOfTheIndex = HISTOGRAM_SINGLE_VALUE,
						value = @Expression(EXPRESSION_BASIC_UNIT_VALUE)
					),
					bucketedPartially = @Expression(EXPRESSION_BUCKETED_PARTIALLY)
				),
				@ScopeReferenceSettings(scope = Scope.ARCHIVED, indexed = ReferenceIndexType.FOR_FILTERING)
			}
		)
		@Nonnull
		List<FeatureValueReference> getFeatureValues();

		/**
		 * Returns all feature values, or empty when the reference was not fetched.
		 */
		@ReferenceRef(REFERENCE_FEATURE_VALUES)
		@Nonnull
		Optional<List<FeatureValueReference>> getFeatureValuesIfAvailable();

	}

	/**
	 * Trait re-declaring the inherited reference with two conditional histograms instead of one.
	 */
	public interface OverridingFeatureValuesTrait extends BaseFeatureValuesTrait {

		/**
		 * Returns all feature values assigned to the product.
		 */
		@Override
		@Reference(
			name = REFERENCE_FEATURE_VALUES,
			entity = ENTITY_FEATURE_VALUE,
			groupEntity = ENTITY_FEATURE,
			scope = {
				@ScopeReferenceSettings(
					scope = Scope.LIVE,
					indexed = ReferenceIndexType.FOR_FILTERING,
					indexedComponents = {
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					},
					facetedPartially = @Expression(EXPRESSION_FACETED_PARTIALLY),
					bucketed = {
						@Histogram(
							nameOfTheIndex = HISTOGRAM_SINGLE_VALUE,
							assignedWhen = @Expression(EXPRESSION_NOT_RANGE),
							value = @Expression(EXPRESSION_BASIC_UNIT_VALUE)
						),
						@Histogram(
							nameOfTheIndex = HISTOGRAM_RANGE_VALUE,
							assignedWhen = @Expression(EXPRESSION_RANGE),
							value = @Expression(EXPRESSION_RANGE_BASIC_UNIT_VALUE)
						)
					},
					bucketedPartially = @Expression(EXPRESSION_BUCKETED_PARTIALLY)
				),
				@ScopeReferenceSettings(scope = Scope.ARCHIVED, indexed = ReferenceIndexType.FOR_FILTERING)
			}
		)
		@Nonnull
		List<FeatureValueReference> getFeatureValues();

	}

	/**
	 * Entity whose feature values reference comes from a trait overriding the inherited declaration.
	 */
	@Entity(name = "RedefinedOverridingProduct")
	public interface OverridingFeaturedProductModel extends OverridingFeatureValuesTrait {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

	}

}
