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

package io.evitadb.externalApi.graphql.api.system.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLUnionType;
import graphql.schema.PropertyDataFetcher;
import graphql.schema.TypeResolver;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Catalog;
import io.evitadb.core.Evita;
import io.evitadb.core.UnusableCatalog;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.UnusableCatalogDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.FinalGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.ChangeCatalogCaptureCriteriaDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.DataSiteDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.SchemaSiteDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.NameVariantDataFetcher;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.AsyncDataFetcher;
import io.evitadb.externalApi.graphql.api.system.model.CatalogQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.CreateCatalogMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.DeleteCatalogIfExistsMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.OnCatalogChangeCaptureSubscriptionHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.OnSystemChangeCaptureSubscriptionHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.RenameCatalogMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.ReplaceCatalogMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.SwitchCatalogToAliveStateMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.SystemRootDescriptor;
import io.evitadb.externalApi.graphql.api.system.resolver.dataFetcher.CatalogDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.dataFetcher.CatalogsDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.dataFetcher.LivenessDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.CreateCatalogMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.DeleteCatalogIfExistsMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.RenameCatalogMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.ReplaceCatalogMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.SwitchCatalogToAliveStateMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.subscribingDataFetcher.ChangeCatalogCaptureBodyDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.subscribingDataFetcher.ChangeSystemCaptureBodyDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.subscribingDataFetcher.OnCatalogChangeCaptureSubscribingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.subscribingDataFetcher.OnSystemChangeCaptureSubscribingDataFetcher;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import java.util.Map;

import static graphql.schema.GraphQLNonNull.nonNull;

/**
 * Implementation of {@link FinalGraphQLSchemaBuilder} for building evitaDB management manipulation schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class SystemGraphQLSchemaBuilder extends FinalGraphQLSchemaBuilder<GraphQLSchemaBuildingContext> {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final NameVariantDataFetcher CAMEL_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.CAMEL_CASE);
	private static final NameVariantDataFetcher PASCAL_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.PASCAL_CASE);
	private static final NameVariantDataFetcher SNAKE_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.SNAKE_CASE);
	private static final NameVariantDataFetcher UPPER_SNAKE_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.UPPER_SNAKE_CASE);
	private static final NameVariantDataFetcher KEBAB_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.KEBAB_CASE);

	private static final PropertyDataFetcher<Map<NamingConvention, String>> CATALOG_NAME_VARIANTS_DATA_FETCHER = PropertyDataFetcher.fetching(it -> ((Catalog) it).getSchema().getNameVariants());
	private static final PropertyDataFetcher<Boolean> CATALOG_SUPPORTS_TRANSACTION_DATA_FETCHER = PropertyDataFetcher.fetching(CatalogContract::supportsTransaction);
	private static final PropertyDataFetcher<Boolean> CATALOG_UNUSABLE_DATA_FETCHER = PropertyDataFetcher.fetching(it -> false);

	private static final PropertyDataFetcher<String> UNUSABLE_CATALOG_STORAGE_PATH_DATA_FETCHER = PropertyDataFetcher.fetching(it -> ((UnusableCatalog) it).getCatalogStoragePath().toString());
	private static final PropertyDataFetcher<String> UNUSABLE_CATALOG_CAUSE_DATA_FETCHER = PropertyDataFetcher.fetching(it -> ((UnusableCatalog) it).getRepresentativeException().toString());
	private static final PropertyDataFetcher<Boolean> UNUSABLE_CATALOG_UNUSABLE_DATA_FETCHER = PropertyDataFetcher.fetching(it -> true);

	@Nonnull
	private final Evita evita;

	public SystemGraphQLSchemaBuilder(@Nonnull GraphQLOptions config, @Nonnull Evita evita) {
		super(new GraphQLSchemaBuildingContext(config, evita));
		this.evita = evita;
	}

	@Override
    @Nonnull
	public GraphQLSchema build() {
		this.buildingContext.registerType(buildNameVariantsObject());

		final GraphQLObjectType catalogObject = buildCatalogObject();
		this.buildingContext.registerType(catalogObject);
		final GraphQLObjectType unusableCatalogObject = buildUnusableCatalogObject();
		this.buildingContext.registerType(unusableCatalogObject);
		this.buildingContext.registerType(buildCatalogUnion(catalogObject, unusableCatalogObject));

		this.buildingContext.registerType(buildChangeSystemCaptureObject());
		this.buildingContext.registerType(buildChangeCatalogCaptureObject());
		this.buildingContext.registerType(SchemaSiteDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(DataSiteDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(ChangeCatalogCaptureCriteriaDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());

		this.buildingContext.registerQueryField(buildLivenessField());
		this.buildingContext.registerQueryField(buildCatalogField());
		this.buildingContext.registerQueryField(buildCatalogsField());

		this.buildingContext.registerMutationField(buildCreateCatalogField());
		this.buildingContext.registerMutationField(buildSwitchCatalogToAliveStateField());
		this.buildingContext.registerMutationField(buildRenameCatalogField());
		this.buildingContext.registerMutationField(buildReplaceCatalogField());
		this.buildingContext.registerMutationField(buildDeleteCatalogIfExistsField());

		this.buildingContext.registerSubscriptionField(buildOnSystemChangeField());
		this.buildingContext.registerSubscriptionField(buildOnCatalogChangeField());

		return this.buildingContext.buildGraphQLSchema();
	}


	@Nonnull
	private GraphQLObjectType buildNameVariantsObject() {
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.CAMEL_CASE,
			CAMEL_CASE_VARIANT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.PASCAL_CASE,
			PASCAL_CASE_VARIANT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.SNAKE_CASE,
			SNAKE_CASE_VARIANT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.UPPER_SNAKE_CASE,
			UPPER_SNAKE_CASE_VARIANT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.KEBAB_CASE,
			KEBAB_CASE_VARIANT_DATA_FETCHER
		);

		return NameVariantsDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildCatalogObject() {
		this.buildingContext.registerDataFetcher(
			CatalogDescriptor.THIS,
			CatalogDescriptor.NAME_VARIANTS,
			CATALOG_NAME_VARIANTS_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			CatalogDescriptor.THIS,
			CatalogDescriptor.SUPPORTS_TRANSACTION,
			CATALOG_SUPPORTS_TRANSACTION_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			CatalogDescriptor.THIS,
			CatalogDescriptor.UNUSABLE,
			CATALOG_UNUSABLE_DATA_FETCHER
		);

		return CatalogDescriptor.THIS.to(this.objectBuilderTransformer).build();
	}

	@Nonnull
	private GraphQLObjectType buildUnusableCatalogObject() {
		this.buildingContext.registerDataFetcher(
			UnusableCatalogDescriptor.THIS,
			UnusableCatalogDescriptor.CATALOG_STORAGE_PATH,
			UNUSABLE_CATALOG_STORAGE_PATH_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			UnusableCatalogDescriptor.THIS,
			UnusableCatalogDescriptor.CAUSE,
			UNUSABLE_CATALOG_CAUSE_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			UnusableCatalogDescriptor.THIS,
			UnusableCatalogDescriptor.UNUSABLE,
			UNUSABLE_CATALOG_UNUSABLE_DATA_FETCHER
		);

		return UnusableCatalogDescriptor.THIS.to(this.objectBuilderTransformer).build();
	}

	@Nonnull
	private GraphQLUnionType buildCatalogUnion(@Nonnull GraphQLObjectType catalogObject,
	                                           @Nonnull GraphQLObjectType unusableCatalogObject) {
		final GraphQLUnionType catalogUnion = CatalogUnionDescriptor.THIS
			.to(this.unionBuilderTransformer)
			.possibleTypes(catalogObject)
			.possibleType(unusableCatalogObject)
			.build();

		final TypeResolver catalogUnionResolver = env -> {
			if (env.getObject() instanceof UnusableCatalog) {
				return unusableCatalogObject;
			} else {
				return catalogObject;
			}
		};
		this.buildingContext.registerTypeResolver(catalogUnion, catalogUnionResolver);

		return catalogUnion;
	}

	@Nonnull
	private GraphQLObjectType buildChangeSystemCaptureObject() {
		this.buildingContext.registerDataFetcher(
			ChangeSystemCaptureDescriptor.THIS,
			ChangeSystemCaptureDescriptor.BODY,
			new ChangeSystemCaptureBodyDataFetcher()
		);

		return ChangeSystemCaptureDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.field(ChangeSystemCaptureDescriptor.BODY.to(this.fieldBuilderTransformer).type(nonNull(GraphQLScalars.OBJECT)))
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildChangeCatalogCaptureObject() {
		this.buildingContext.registerDataFetcher(
			ChangeCatalogCaptureDescriptor.THIS,
			ChangeCatalogCaptureDescriptor.BODY,
			new ChangeCatalogCaptureBodyDataFetcher(OBJECT_MAPPER)
		);

		return ChangeCatalogCaptureDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.field(ChangeCatalogCaptureDescriptor.BODY.to(this.fieldBuilderTransformer).type(nonNull(GraphQLScalars.OBJECT)))
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildLivenessField() {
		return new BuiltFieldDescriptor(
			SystemRootDescriptor.LIVENESS.to(this.staticEndpointBuilderTransformer).build(),
			new AsyncDataFetcher(
				LivenessDataFetcher.getInstance(),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCatalogField() {
		final GraphQLFieldDefinition catalogField = SystemRootDescriptor.CATALOG
			.to(this.staticEndpointBuilderTransformer)
			.argument(CatalogQueryHeaderDescriptor.NAME.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			catalogField,
			new AsyncDataFetcher(
				new CatalogDataFetcher(this.evita),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCatalogsField() {
		return new BuiltFieldDescriptor(
			SystemRootDescriptor.CATALOGS.to(this.staticEndpointBuilderTransformer).build(),
			new AsyncDataFetcher(
				new CatalogsDataFetcher(this.evita),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCreateCatalogField() {
		final GraphQLFieldDefinition createCatalogField = SystemRootDescriptor.CREATE_CATALOG
			.to(this.staticEndpointBuilderTransformer)
			.argument(CreateCatalogMutationHeaderDescriptor.NAME.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			createCatalogField,
			new AsyncDataFetcher(
				new CreateCatalogMutatingDataFetcher(this.evita),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildSwitchCatalogToAliveStateField() {
		final GraphQLFieldDefinition switchCatalogToAliveStateField = SystemRootDescriptor.SWITCH_CATALOG_TO_ALIVE_STATE
			.to(this.staticEndpointBuilderTransformer)
			.argument(SwitchCatalogToAliveStateMutationHeaderDescriptor.NAME.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			switchCatalogToAliveStateField,
			new AsyncDataFetcher(
				new SwitchCatalogToAliveStateMutatingDataFetcher(this.evita),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildRenameCatalogField() {
		final GraphQLFieldDefinition renameCatalogField = SystemRootDescriptor.RENAME_CATALOG
			.to(this.staticEndpointBuilderTransformer)
			.argument(RenameCatalogMutationHeaderDescriptor.NAME.to(this.argumentBuilderTransformer))
			.argument(RenameCatalogMutationHeaderDescriptor.NEW_NAME.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			renameCatalogField,
			new AsyncDataFetcher(
				new RenameCatalogMutatingDataFetcher(this.evita),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReplaceCatalogField() {
		final GraphQLFieldDefinition replaceCatalogField = SystemRootDescriptor.REPLACE_CATALOG
			.to(this.staticEndpointBuilderTransformer)
			.argument(ReplaceCatalogMutationHeaderDescriptor.NAME_TO_BE_REPLACED.to(this.argumentBuilderTransformer))
			.argument(ReplaceCatalogMutationHeaderDescriptor.NAME_TO_BE_REPLACED_WITH.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			replaceCatalogField,
			new AsyncDataFetcher(
				new ReplaceCatalogMutatingDataFetcher(this.evita),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildDeleteCatalogIfExistsField() {
		final GraphQLFieldDefinition deleteCatalogIfExistsCatalogField = SystemRootDescriptor.DELETE_CATALOG_IF_EXISTS
			.to(this.staticEndpointBuilderTransformer)
			.argument(DeleteCatalogIfExistsMutationHeaderDescriptor.NAME.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			deleteCatalogIfExistsCatalogField,
			new AsyncDataFetcher(
				new DeleteCatalogIfExistsMutatingDataFetcher(this.evita),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildOnSystemChangeField() {
		final GraphQLFieldDefinition onSystemChangeCaptureField = SystemRootDescriptor.ON_SYSTEM_CHANGE
			.to(this.staticEndpointBuilderTransformer)
			.argument(OnSystemChangeCaptureSubscriptionHeaderDescriptor.SINCE_VERSION.to(this.argumentBuilderTransformer))
			.argument(OnSystemChangeCaptureSubscriptionHeaderDescriptor.SINCE_INDEX.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			onSystemChangeCaptureField,
			new OnSystemChangeCaptureSubscribingDataFetcher(this.evita)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildOnCatalogChangeField() {
		final GraphQLFieldDefinition onSystemChangeCaptureField = SystemRootDescriptor.ON_CATALOG_CHANGE
			.to(this.staticEndpointBuilderTransformer)
			.argument(OnCatalogChangeCaptureSubscriptionHeaderDescriptor.CATALOG_NAME.to(this.argumentBuilderTransformer))
			.argument(OnCatalogChangeCaptureSubscriptionHeaderDescriptor.SINCE_VERSION.to(this.argumentBuilderTransformer))
			.argument(OnCatalogChangeCaptureSubscriptionHeaderDescriptor.SINCE_INDEX.to(this.argumentBuilderTransformer))
			.argument(OnCatalogChangeCaptureSubscriptionHeaderDescriptor.CRITERIA.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			onSystemChangeCaptureField,
			new OnCatalogChangeCaptureSubscribingDataFetcher(this.evita)
		);
	}
}
