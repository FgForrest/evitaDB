
/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation;

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextLanguageSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextResourceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextLanguageSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextResourceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextLanguageSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextResourceSchemaMutation;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.MutationCase;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutationConverter;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.MutationCase.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of {@link LocalCatalogSchemaMutation} and {@link GrpcLocalCatalogSchemaMutation} by delegating
 * each mutation to specific converter.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DelegatingLocalCatalogSchemaMutationConverter implements SchemaMutationConverter<LocalCatalogSchemaMutation, GrpcLocalCatalogSchemaMutation> {
	public static final DelegatingLocalCatalogSchemaMutationConverter INSTANCE = new DelegatingLocalCatalogSchemaMutationConverter();

	private static final Map<Class<? extends LocalCatalogSchemaMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(24);
		// catalog schema mutations
		TO_GRPC_CONVERTERS.put(ModifyCatalogSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyCatalogSchemaDescriptionMutation((GrpcModifyCatalogSchemaDescriptionMutation) m), ModifyCatalogSchemaDescriptionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyCatalogSchemaConflictResolutionMutation.class, new ToGrpc((b, m) -> b.setModifyCatalogSchemaConflictResolutionMutation((GrpcModifyCatalogSchemaConflictResolutionMutation) m), ModifyCatalogSchemaConflictResolutionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(AllowEvolutionModeInCatalogSchemaMutation.class, new ToGrpc((b, m) -> b.setAllowEvolutionModeInCatalogSchemaMutation((GrpcAllowEvolutionModeInCatalogSchemaMutation) m), AllowEvolutionModeInCatalogSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(DisallowEvolutionModeInCatalogSchemaMutation.class, new ToGrpc((b, m) -> b.setDisallowEvolutionModeInCatalogSchemaMutation((GrpcDisallowEvolutionModeInCatalogSchemaMutation) m), DisallowEvolutionModeInCatalogSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(CreateFulltextLanguageSchemaMutation.class,
			new ToGrpc((b, m) -> b.setCreateFulltextLanguageSchemaMutation(
				(GrpcCreateFulltextLanguageSchemaMutation) m),
				CreateFulltextLanguageSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyFulltextLanguageSchemaMutation.class,
			new ToGrpc((b, m) -> b.setModifyFulltextLanguageSchemaMutation(
				(GrpcModifyFulltextLanguageSchemaMutation) m),
				ModifyFulltextLanguageSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveFulltextLanguageSchemaMutation.class,
			new ToGrpc((b, m) -> b.setRemoveFulltextLanguageSchemaMutation(
				(GrpcRemoveFulltextLanguageSchemaMutation) m),
				RemoveFulltextLanguageSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(CreateFulltextResourceSchemaMutation.class,
			new ToGrpc((b, m) -> b.setCreateFulltextResourceSchemaMutation(
				(GrpcCreateFulltextResourceSchemaMutation) m),
				CreateFulltextResourceSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyFulltextResourceSchemaMutation.class,
			new ToGrpc((b, m) -> b.setModifyFulltextResourceSchemaMutation(
				(GrpcModifyFulltextResourceSchemaMutation) m),
				ModifyFulltextResourceSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveFulltextResourceSchemaMutation.class,
			new ToGrpc((b, m) -> b.setRemoveFulltextResourceSchemaMutation(
				(GrpcRemoveFulltextResourceSchemaMutation) m),
				RemoveFulltextResourceSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(CreateFulltextAnalyzerSchemaMutation.class,
			new ToGrpc((b, m) -> b.setCreateFulltextAnalyzerSchemaMutation(
				(GrpcCreateFulltextAnalyzerSchemaMutation) m),
				CreateFulltextAnalyzerSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyFulltextAnalyzerSchemaMutation.class,
			new ToGrpc((b, m) -> b.setModifyFulltextAnalyzerSchemaMutation(
				(GrpcModifyFulltextAnalyzerSchemaMutation) m),
				ModifyFulltextAnalyzerSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveFulltextAnalyzerSchemaMutation.class,
			new ToGrpc((b, m) -> b.setRemoveFulltextAnalyzerSchemaMutation(
				(GrpcRemoveFulltextAnalyzerSchemaMutation) m),
				RemoveFulltextAnalyzerSchemaMutationConverter.INSTANCE));
		// global attribute schema mutations
		TO_GRPC_CONVERTERS.put(CreateGlobalAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateGlobalAttributeSchemaMutation((GrpcCreateGlobalAttributeSchemaMutation) m), CreateGlobalAttributeSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDefaultValueMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDefaultValueMutation((GrpcModifyAttributeSchemaDefaultValueMutation) m), ModifyAttributeSchemaDefaultValueMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDeprecationNoticeMutation((GrpcModifyAttributeSchemaDeprecationNoticeMutation) m), ModifyAttributeSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDescriptionMutation((GrpcModifyAttributeSchemaDescriptionMutation) m), ModifyAttributeSchemaDescriptionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaNameMutation((GrpcModifyAttributeSchemaNameMutation) m), ModifyAttributeSchemaNameMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaTypeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaTypeMutation((GrpcModifyAttributeSchemaTypeMutation) m), ModifyAttributeSchemaTypeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveAttributeSchemaMutation((GrpcRemoveAttributeSchemaMutation) m), RemoveAttributeSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaFilterableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaFilterableMutation((GrpcSetAttributeSchemaFilterableMutation) m), SetAttributeSchemaFilterableMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(
			SetAttributeSchemaAcceleratedMutation.class,
			new ToGrpc((b, m) -> b.setSetAttributeSchemaAcceleratedMutation((GrpcSetAttributeSchemaAcceleratedMutation) m), SetAttributeSchemaAcceleratedMutationConverter.INSTANCE)
		);
		TO_GRPC_CONVERTERS.put(
			SetAttributeSchemaNormalizedMutation.class,
			new ToGrpc((b, m) -> b.setSetAttributeSchemaNormalizedMutation((GrpcSetAttributeSchemaNormalizedMutation) m),
				SetAttributeSchemaNormalizedMutationConverter.INSTANCE)
		);
		TO_GRPC_CONVERTERS.put(
			SetAttributeSchemaSearchableMutation.class,
			new ToGrpc(
				(b, m) -> b.setSetAttributeSchemaSearchableMutation((GrpcSetAttributeSchemaSearchableMutation) m),
				SetAttributeSchemaSearchableMutationConverter.INSTANCE
			)
		);
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaGloballyUniqueMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaGloballyUniqueMutation((GrpcSetAttributeSchemaGloballyUniqueMutation) m), SetAttributeSchemaGloballyUniqueMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaLocalizedMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaLocalizedMutation((GrpcSetAttributeSchemaLocalizedMutation) m), SetAttributeSchemaLocalizedMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaNullableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaNullableMutation((GrpcSetAttributeSchemaNullableMutation) m), SetAttributeSchemaNullableMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaConflictResolutionOverrideMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaConflictResolutionOverrideMutation((GrpcSetAttributeSchemaConflictResolutionOverrideMutation) m), SetAttributeSchemaConflictResolutionOverrideMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaRepresentativeMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaRepresentativeMutation((GrpcSetAttributeSchemaRepresentativeMutation) m), SetAttributeSchemaRepresentativeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaSortableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaSortableMutation((GrpcSetAttributeSchemaSortableMutation) m), SetAttributeSchemaSortableMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaUniqueMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaUniqueMutation((GrpcSetAttributeSchemaUniqueMutation) m), SetAttributeSchemaUniqueMutationConverter.INSTANCE));
		// entity schema mutations
		TO_GRPC_CONVERTERS.put(CreateEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setCreateEntitySchemaMutation((GrpcCreateEntitySchemaMutation) m), CreateEntitySchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setModifyEntitySchemaMutation((GrpcModifyEntitySchemaMutation) m), ModifyEntitySchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyEntitySchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyEntitySchemaNameMutation((GrpcModifyEntitySchemaNameMutation) m), ModifyEntitySchemaNameMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveEntitySchemaMutation((GrpcRemoveEntitySchemaMutation) m), RemoveEntitySchemaMutationConverter.INSTANCE));

		TO_JAVA_CONVERTERS = createHashMap(24);
		// catalog schema mutations
		TO_JAVA_CONVERTERS.put(MODIFYCATALOGSCHEMADESCRIPTIONMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyCatalogSchemaDescriptionMutation, ModifyCatalogSchemaDescriptionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYCATALOGSCHEMACONFLICTRESOLUTIONMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyCatalogSchemaConflictResolutionMutation, ModifyCatalogSchemaConflictResolutionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(
			ALLOWEVOLUTIONMODEINCATALOGSCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getAllowEvolutionModeInCatalogSchemaMutation,
				AllowEvolutionModeInCatalogSchemaMutationConverter.INSTANCE)
		);
		TO_JAVA_CONVERTERS.put(
			DISALLOWEVOLUTIONMODEINCATALOGSCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getDisallowEvolutionModeInCatalogSchemaMutation,
				DisallowEvolutionModeInCatalogSchemaMutationConverter.INSTANCE)
		);
		TO_JAVA_CONVERTERS.put(CREATEFULLTEXTLANGUAGESCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getCreateFulltextLanguageSchemaMutation,
				CreateFulltextLanguageSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYFULLTEXTLANGUAGESCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getModifyFulltextLanguageSchemaMutation,
				ModifyFulltextLanguageSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEFULLTEXTLANGUAGESCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getRemoveFulltextLanguageSchemaMutation,
				RemoveFulltextLanguageSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(CREATEFULLTEXTRESOURCESCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getCreateFulltextResourceSchemaMutation,
				CreateFulltextResourceSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYFULLTEXTRESOURCESCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getModifyFulltextResourceSchemaMutation,
				ModifyFulltextResourceSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEFULLTEXTRESOURCESCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getRemoveFulltextResourceSchemaMutation,
				RemoveFulltextResourceSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(CREATEFULLTEXTANALYZERSCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getCreateFulltextAnalyzerSchemaMutation,
				CreateFulltextAnalyzerSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYFULLTEXTANALYZERSCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getModifyFulltextAnalyzerSchemaMutation,
				ModifyFulltextAnalyzerSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEFULLTEXTANALYZERSCHEMAMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getRemoveFulltextAnalyzerSchemaMutation,
				RemoveFulltextAnalyzerSchemaMutationConverter.INSTANCE));
		// global attribute schema mutations
		TO_JAVA_CONVERTERS.put(CREATEGLOBALATTRIBUTESCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getCreateGlobalAttributeSchemaMutation, CreateGlobalAttributeSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEFAULTVALUEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaDefaultValueMutation, ModifyAttributeSchemaDefaultValueMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaDeprecationNoticeMutation, ModifyAttributeSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADESCRIPTIONMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaDescriptionMutation, ModifyAttributeSchemaDescriptionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMANAMEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaNameMutation, ModifyAttributeSchemaNameMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMATYPEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaTypeMutation, ModifyAttributeSchemaTypeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getRemoveAttributeSchemaMutation, RemoveAttributeSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAFILTERABLEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaFilterableMutation, SetAttributeSchemaFilterableMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(
			SETATTRIBUTESCHEMAACCELERATEDMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaAcceleratedMutation, SetAttributeSchemaAcceleratedMutationConverter.INSTANCE)
		);
		TO_JAVA_CONVERTERS.put(
			SETATTRIBUTESCHEMANORMALIZEDMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaNormalizedMutation,
				SetAttributeSchemaNormalizedMutationConverter.INSTANCE)
		);
		TO_JAVA_CONVERTERS.put(
			SETATTRIBUTESCHEMASEARCHABLEMUTATION,
			new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaSearchableMutation,
				SetAttributeSchemaSearchableMutationConverter.INSTANCE)
		);
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAGLOBALLYUNIQUEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaGloballyUniqueMutation, SetAttributeSchemaGloballyUniqueMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMALOCALIZEDMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaLocalizedMutation, SetAttributeSchemaLocalizedMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMANULLABLEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaNullableMutation, SetAttributeSchemaNullableMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMACONFLICTRESOLUTIONOVERRIDEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaConflictResolutionOverrideMutation, SetAttributeSchemaConflictResolutionOverrideMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAREPRESENTATIVEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaRepresentativeMutation, SetAttributeSchemaRepresentativeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMASORTABLEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaSortableMutation, SetAttributeSchemaSortableMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAUNIQUEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaUniqueMutation, SetAttributeSchemaUniqueMutationConverter.INSTANCE));
		// entity schema mutations
		TO_JAVA_CONVERTERS.put(CREATEENTITYSCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getCreateEntitySchemaMutation, CreateEntitySchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYENTITYSCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyEntitySchemaMutation, ModifyEntitySchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYENTITYSCHEMANAMEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyEntitySchemaNameMutation, ModifyEntitySchemaNameMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEENTITYSCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getRemoveEntitySchemaMutation, RemoveEntitySchemaMutationConverter.INSTANCE));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcLocalCatalogSchemaMutation convert(@Nonnull LocalCatalogSchemaMutation mutation) {
		final ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final SchemaMutationConverter<LocalCatalogSchemaMutation, ?> converter =
			(SchemaMutationConverter<LocalCatalogSchemaMutation, ?>) conversionDescriptor.converter();

		final GrpcLocalCatalogSchemaMutation.Builder builder = GrpcLocalCatalogSchemaMutation.newBuilder();
		final BiConsumer<GrpcLocalCatalogSchemaMutation.Builder, Message> mutationSetter = (BiConsumer<GrpcLocalCatalogSchemaMutation.Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public LocalCatalogSchemaMutation convert(@Nonnull GrpcLocalCatalogSchemaMutation mutation) {
		final ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

		final Function<GrpcLocalCatalogSchemaMutation, Message> extractor =
			(Function<GrpcLocalCatalogSchemaMutation, Message>) conversionDescriptor.mutationExtractor();
		final SchemaMutationConverter<LocalCatalogSchemaMutation, Message> converter =
			(SchemaMutationConverter<LocalCatalogSchemaMutation, Message>) conversionDescriptor.converter();
		return converter.convert(extractor.apply(mutation));
	}

	private record ToJava(@Nonnull Function<GrpcLocalCatalogSchemaMutation, ? extends Message> mutationExtractor,
	                      @Nonnull SchemaMutationConverter<? extends LocalCatalogSchemaMutation, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<GrpcLocalCatalogSchemaMutation.Builder, ? extends Message> mutationSetter,
	                      @Nonnull SchemaMutationConverter<? extends LocalCatalogSchemaMutation, ?> converter) {}
}
