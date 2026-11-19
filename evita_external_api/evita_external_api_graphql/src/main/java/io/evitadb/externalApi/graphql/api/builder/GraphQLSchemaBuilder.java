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

package io.evitadb.externalApi.graphql.api.builder;

import graphql.Directives;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLUnionType;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaSearchableMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateFulltextAnalyzerSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateFulltextLanguageSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateFulltextResourceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyFulltextAnalyzerSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyFulltextLanguageSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyFulltextResourceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.TypeDescriptor;
import io.evitadb.externalApi.api.model.UnionDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.dataType.DataTypeSerializer;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.graphql.api.catalog.resolver.dataFetcher.MappingTypeResolver.RegistryKey;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.AssociatedDataMutationTextExpressionDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.FulltextAnalyzerMutationAllLanguagesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.FulltextAnalyzerMutationLanguagesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.FulltextLanguageMutationWordSplittingDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.FulltextResourceMutationContentDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.MutationDtoTypeResolver;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.NameVariantDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.SchemaMutationSearchWeightDataFetcher;
import io.evitadb.externalApi.graphql.api.model.*;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.MutationTypeDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static graphql.schema.GraphQLEnumType.newEnum;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.ASSOCIATED_DATA_SCALAR_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Common ancestor for all {@link graphql.schema.GraphQLSchema} builders.
 *
 * Builder should be mainly composed of `build...Field` and `build...Object` methods starting by `build...Field` method because
 * fields are what clients see. Then, for those field backing objects are created. For those objects another fields are created
 * and so on.
 *
 * @see PartialGraphQLSchemaBuilder
 * @see FinalGraphQLSchemaBuilder
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class GraphQLSchemaBuilder<C extends GraphQLSchemaBuildingContext> {

	protected static final RegistryKey<Class<? extends Mutation>> MUTATION_INTERFACE_TYPE_RESOLVER_REGISTRY_KEY = new RegistryKey<>();

	private static final NameVariantDataFetcher CAMEL_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.CAMEL_CASE);
	private static final NameVariantDataFetcher PASCAL_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.PASCAL_CASE);
	private static final NameVariantDataFetcher SNAKE_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.SNAKE_CASE);
	private static final NameVariantDataFetcher UPPER_SNAKE_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.UPPER_SNAKE_CASE);
	private static final NameVariantDataFetcher KEBAB_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.KEBAB_CASE);

	@Nonnull protected final PropertyDataTypeDescriptorToGraphQLTypeTransformer propertyDataTypeBuilderTransformer;
	@Nonnull protected final EndpointDescriptorToGraphQLFieldTransformer staticEndpointBuilderTransformer;
	@Nonnull protected final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull protected final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull protected final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull protected final UnionDescriptorToGraphQLUnionTransformer unionBuilderTransformer;
	@Nonnull protected final ObjectDescriptorToGraphQLInputObjectTransformer inputObjectBuilderTransformer;
	@Nonnull protected final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;
	@Nonnull protected final PropertyDescriptorToGraphQLInputFieldTransformer inputFieldBuilderTransformer;

	@Nonnull
	protected final C buildingContext;

	protected GraphQLSchemaBuilder(@Nonnull C buildingContext) {
		this.buildingContext = buildingContext;
		this.propertyDataTypeBuilderTransformer = new PropertyDataTypeDescriptorToGraphQLTypeTransformer(buildingContext);
		this.staticEndpointBuilderTransformer = new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer);
		this.argumentBuilderTransformer = new PropertyDescriptorToGraphQLArgumentTransformer(this.propertyDataTypeBuilderTransformer);
		this.fieldBuilderTransformer = new PropertyDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer);
		this.inputFieldBuilderTransformer = new PropertyDescriptorToGraphQLInputFieldTransformer(this.propertyDataTypeBuilderTransformer);
		this.interfaceBuilderTransformer = new ObjectDescriptorToGraphQLInterfaceTransformer(this.fieldBuilderTransformer);
		this.objectBuilderTransformer = new ObjectDescriptorToGraphQLObjectTransformer(this.fieldBuilderTransformer);
		this.unionBuilderTransformer = new UnionDescriptorToGraphQLUnionTransformer();
		this.inputObjectBuilderTransformer = new ObjectDescriptorToGraphQLInputObjectTransformer(this.inputFieldBuilderTransformer);
	}


	/**
	 * Registers typed provider parameters and phase-specific `@oneOf` selectors for normalization and fulltext
	 * declarations accepted by the schema API.
	 */
	protected void registerAnalysisStepInputTypes() {
		registerAnalysisStepInputTypes(AnalysisStepInputDescriptors::getSteps);
	}

	/**
	 * Registers phase selectors using the same provider metadata as the server input converter.
	 *
	 * @param providers ordered metadata of the validated providers admitted in each phase
	 */
	protected void registerAnalysisStepInputTypes(
		@Nonnull Function<AnalysisStepInputDescriptors.Context, List<AnalysisStepInputDescriptors.StepInput>> providers
	) {
		final Map<String, ObjectDescriptor> parameters = new HashMap<>();
		for (final AnalysisStepInputDescriptors.Context context : AnalysisStepInputDescriptors.Context.values()) {
			final List<AnalysisStepInputDescriptors.StepInput> steps = providers.apply(context);
			final ObjectDescriptor descriptor = AnalysisStepInputDescriptors.getInputDescriptor(context, steps);
			final GraphQLInputObjectType.Builder input = descriptor.to(this.inputObjectBuilderTransformer)
				.clearFields().withAppliedDirective(Directives.OneOfDirective.toAppliedDirective());
			for (final AnalysisStepInputDescriptors.StepInput step : steps) {
				if (step.parameters() != null) {
					final ObjectDescriptor previous =
						parameters.putIfAbsent(step.parameters().name(), step.parameters());
					if (previous != null && !previous.equals(step.parameters())) {
						throw new ExternalApiInternalError("Conflicting analysis parameter type `" +
							step.parameters().name() + "`.");
					}
					if (previous == null) {
						this.buildingContext.registerType(step.parameters()
							.to(this.inputObjectBuilderTransformer).build());
					}
				}
				input.field(descriptor.staticProperties().stream()
					.filter(property -> property.name().equals(step.fieldName()))
					.findFirst().orElseThrow().to(this.inputFieldBuilderTransformer));
			}
			this.buildingContext.registerType(input.build());
		}
		for (final ObjectDescriptor descriptor : List.of(
			SynonymGroupDescriptor.THIS_INPUT, FulltextResourceContentDescriptor.THIS_INPUT,
			FulltextAnalysisPhasesDescriptor.THIS_INPUT, FulltextLanguageAnalysisPhasesDescriptor.THIS_INPUT)) {
			this.buildingContext.registerType(descriptor.to(this.inputObjectBuilderTransformer).build());
		}
	}

	/** Registers the shared fulltext output objects in a catalog or system schema. */
	protected void registerFulltextOutputTypes() {
		for (final ObjectDescriptor descriptor : List.of(
			SynonymGroupDescriptor.THIS, FulltextResourceContentDescriptor.THIS, FulltextAnalysisPhasesDescriptor.THIS,
			FulltextLanguageAnalysisPhasesDescriptor.THIS, FulltextLanguageSchemaDescriptor.THIS,
			FulltextResourceSchemaDescriptor.THIS, FulltextAnalyzerSchemaDescriptor.THIS)) {
			this.buildingContext.registerType(descriptor.to(this.objectBuilderTransformer).build());
		}
	}


	@Nonnull
	protected static GraphQLEnumType buildScalarEnum() {
		final GraphQLEnumType.Builder scalarBuilder = newEnum()
			.name(SCALAR_ENUM.name())
			.description(SCALAR_ENUM.description());

		registerScalarValue(scalarBuilder, String.class);
		registerScalarValue(scalarBuilder, String[].class);
		registerScalarValue(scalarBuilder, Byte.class);
		registerScalarValue(scalarBuilder, Byte[].class);
		registerScalarValue(scalarBuilder, Short.class);
		registerScalarValue(scalarBuilder, Short[].class);
		registerScalarValue(scalarBuilder, Integer.class);
		registerScalarValue(scalarBuilder, Integer[].class);
		registerScalarValue(scalarBuilder, Long.class);
		registerScalarValue(scalarBuilder, Long[].class);
		registerScalarValue(scalarBuilder, Boolean.class);
		registerScalarValue(scalarBuilder, Boolean[].class);
		registerScalarValue(scalarBuilder, Character.class);
		registerScalarValue(scalarBuilder, Character[].class);
		registerScalarValue(scalarBuilder, BigDecimal.class);
		registerScalarValue(scalarBuilder, BigDecimal[].class);
		registerScalarValue(scalarBuilder, OffsetDateTime.class);
		registerScalarValue(scalarBuilder, OffsetDateTime[].class);
		registerScalarValue(scalarBuilder, LocalDateTime.class);
		registerScalarValue(scalarBuilder, LocalDateTime[].class);
		registerScalarValue(scalarBuilder, LocalDate.class);
		registerScalarValue(scalarBuilder, LocalDate[].class);
		registerScalarValue(scalarBuilder, LocalTime.class);
		registerScalarValue(scalarBuilder, LocalTime[].class);
		registerScalarValue(scalarBuilder, DateTimeRange.class);
		registerScalarValue(scalarBuilder, DateTimeRange[].class);
		registerScalarValue(scalarBuilder, BigDecimalNumberRange.class);
		registerScalarValue(scalarBuilder, BigDecimalNumberRange[].class);
		registerScalarValue(scalarBuilder, ByteNumberRange.class);
		registerScalarValue(scalarBuilder, ByteNumberRange[].class);
		registerScalarValue(scalarBuilder, ShortNumberRange.class);
		registerScalarValue(scalarBuilder, ShortNumberRange[].class);
		registerScalarValue(scalarBuilder, IntegerNumberRange.class);
		registerScalarValue(scalarBuilder, IntegerNumberRange[].class);
		registerScalarValue(scalarBuilder, LongNumberRange.class);
		registerScalarValue(scalarBuilder, LongNumberRange[].class);
		registerScalarValue(scalarBuilder, Locale.class);
		registerScalarValue(scalarBuilder, Locale[].class);
		registerScalarValue(scalarBuilder, Currency.class);
		registerScalarValue(scalarBuilder, Currency[].class);
		registerScalarValue(scalarBuilder, UUID.class);
		registerScalarValue(scalarBuilder, UUID[].class);
		registerScalarValue(scalarBuilder, Predecessor.class);
		registerScalarValue(scalarBuilder, ReferencedEntityPredecessor.class);
		registerScalarValue(scalarBuilder, ComplexDataObject.class);
		registerScalarValue(scalarBuilder, ExpressionNode.class);

		return scalarBuilder.build();
	}

	@Nonnull
	protected static GraphQLEnumType buildAssociatedDataScalarEnum(@Nonnull GraphQLEnumType scalarEnum) {
		final GraphQLEnumType.Builder scalarBuilder = newEnum(scalarEnum)
			.name(ASSOCIATED_DATA_SCALAR_ENUM.name())
			.description(ASSOCIATED_DATA_SCALAR_ENUM.description());

		registerScalarValue(scalarBuilder, ComplexDataObject.class);

		return scalarBuilder.build();
	}

	/**
	 * Builds GraphQL object type for name variants and registers
	 * the corresponding data fetchers for each naming convention.
	 */
	@Nonnull
	protected static GraphQLObjectType buildNameVariantsObject(
		@Nonnull GraphQLSchemaBuildingContext buildingContext,
		@Nonnull ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer
	) {
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.CAMEL_CASE,
			CAMEL_CASE_VARIANT_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.PASCAL_CASE,
			PASCAL_CASE_VARIANT_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.SNAKE_CASE,
			SNAKE_CASE_VARIANT_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.UPPER_SNAKE_CASE,
			UPPER_SNAKE_CASE_VARIANT_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.KEBAB_CASE,
			KEBAB_CASE_VARIANT_DATA_FETCHER
		);

		return NameVariantsDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	private static void registerScalarValue(@Nonnull GraphQLEnumType.Builder scalarBuilder,
	                                        @Nonnull Class<? extends Serializable> javaType) {
		final String apiName = DataTypeSerializer.serialize(javaType);
		scalarBuilder.value(apiName, javaType);
	}

	protected void buildMutationInterface() {
		final GraphQLInterfaceType mutationInterface = MutationDescriptor.THIS_INTERFACE.to(this.interfaceBuilderTransformer).build();
		this.buildingContext.registerType(mutationInterface);
		this.buildingContext.addMappingTypeResolver(
			mutationInterface,
			MUTATION_INTERFACE_TYPE_RESOLVER_REGISTRY_KEY,
			new MutationDtoTypeResolver(120)
		);
	}

	protected void registerInputMutations(@Nonnull ObjectDescriptor... mutationDescriptors) {
		for (final ObjectDescriptor mutationDescriptor : mutationDescriptors) {
			final GraphQLInputObjectType mutationType = mutationDescriptor.to(this.inputObjectBuilderTransformer).build();
			this.buildingContext.registerType(mutationType);
		}
	}

	@Nonnull
	protected Map<Class<? extends Mutation>, GraphQLObjectType> registerOutputMutations(@Nonnull ObjectDescriptor... mutationDescriptors) {
		final Map<Class<? extends Mutation>, GraphQLObjectType> builtMutationObjects = createHashMap(mutationDescriptors.length);

		for (final ObjectDescriptor mutationDescriptor : mutationDescriptors) {
			if (!Mutation.class.isAssignableFrom(mutationDescriptor.representedClass())) {
				throw new GraphQLSchemaBuildingError("Mutation descriptor " + mutationDescriptor.getClass().getName() + " does not represent a Mutation class.");
			}

			// create an object
			final GraphQLObjectType mutationType = mutationDescriptor.to(this.objectBuilderTransformer).build();
			this.buildingContext.registerType(mutationType);

			// register additional field data fetchers
			this.buildingContext.registerDataFetcher(
				mutationDescriptor.name(),
				MutationDescriptor.MUTATION_TYPE,
				MutationTypeDataFetcher.getInstance()
			);

			// the default property fetcher would leave the `Expression` to the string scalar, which renders its
			// `toString()` display form, whereas clients need the raw source they can send back as mutation input
			if (mutationDescriptor.representedClass() == CreateAssociatedDataSchemaMutation.class ||
				mutationDescriptor.representedClass() == SetAssociatedDataSchemaSearchableMutation.class) {
				this.buildingContext.registerDataFetcher(
					mutationDescriptor.name(),
					SetAssociatedDataSchemaSearchableMutationDescriptor.TEXT_EXPRESSION,
					AssociatedDataMutationTextExpressionDataFetcher.getInstance()
				);
			}

			// ranking weight properties share a decimal fetcher and are matched by name,
			// so a new weight property must use one of these names or register its own fetcher
			for (final PropertyDescriptor property : mutationDescriptor.staticProperties()) {
				if ("searchWeight".equals(property.name()) || "searchableThroughWeight".equals(property.name()) ||
					"weight".equals(property.name())) {
					this.buildingContext.registerDataFetcher(
						mutationDescriptor.name(), property, SchemaMutationSearchWeightDataFetcher.getInstance());
				}
			}

			if (mutationDescriptor == CreateFulltextLanguageSchemaMutationDescriptor.THIS ||
				mutationDescriptor == ModifyFulltextLanguageSchemaMutationDescriptor.THIS) {
				this.buildingContext.registerDataFetcher(
					mutationDescriptor.name(), CreateFulltextLanguageSchemaMutationDescriptor.WORD_SPLITTING,
					FulltextLanguageMutationWordSplittingDataFetcher.getInstance());
			} else if (mutationDescriptor == CreateFulltextResourceSchemaMutationDescriptor.THIS ||
				mutationDescriptor == ModifyFulltextResourceSchemaMutationDescriptor.THIS) {
				this.buildingContext.registerDataFetcher(
					mutationDescriptor.name(), CreateFulltextResourceSchemaMutationDescriptor.CONTENT,
					FulltextResourceMutationContentDataFetcher.getInstance());
			} else if (mutationDescriptor == CreateFulltextAnalyzerSchemaMutationDescriptor.THIS ||
				mutationDescriptor == ModifyFulltextAnalyzerSchemaMutationDescriptor.THIS) {
				this.buildingContext.registerDataFetcher(
					mutationDescriptor.name(), CreateFulltextAnalyzerSchemaMutationDescriptor.ALL_LANGUAGES,
					FulltextAnalyzerMutationAllLanguagesDataFetcher.getInstance());
				this.buildingContext.registerDataFetcher(
					mutationDescriptor.name(), CreateFulltextAnalyzerSchemaMutationDescriptor.LANGUAGES,
					FulltextAnalyzerMutationLanguagesDataFetcher.getInstance());
			}

			// register dto mapping
			//noinspection unchecked
			this.buildingContext.getMappingTypeResolver(MUTATION_INTERFACE_TYPE_RESOLVER_REGISTRY_KEY).registerTypeMapping(
				(Class<? extends Mutation>) mutationDescriptor.representedClass(),
				mutationType
			);

			//noinspection unchecked
			builtMutationObjects.put((Class<? extends Mutation>) mutationDescriptor.representedClass(), mutationType);
		}

		return builtMutationObjects;
	}

	protected void registerMutationUnion(
		@Nonnull UnionDescriptor unionDescriptor,
		@Nonnull Map<Class<? extends Mutation>, GraphQLObjectType> outputMutationObjectRegistry
	) {
		final GraphQLUnionType union = unionDescriptor.to(this.unionBuilderTransformer).build();
		this.buildingContext.registerType(union);

		final MutationDtoTypeResolver resolver = new MutationDtoTypeResolver(unionDescriptor.types().size());
		for (TypeDescriptor type : unionDescriptor.types()) {
			// we need to pair the built GraphQL mutation object for the type descriptor to the union type resolver
			@SuppressWarnings("unchecked") final Class<? extends Mutation> mutationClass = (Class<? extends Mutation>) ((ObjectDescriptor) type).representedClass();
			final GraphQLObjectType mutationObject = outputMutationObjectRegistry.get(mutationClass);
			Assert.isPremiseValid(
				mutationObject != null,
				() -> new GraphQLSchemaBuildingError("Mutation object for class `" + mutationClass.getName() + "` could not be found.")
			);
			resolver.registerTypeMapping(mutationClass, mutationObject);
		}

		this.buildingContext.addMappingTypeResolver(union, new RegistryKey<>(), resolver);
	}
}
