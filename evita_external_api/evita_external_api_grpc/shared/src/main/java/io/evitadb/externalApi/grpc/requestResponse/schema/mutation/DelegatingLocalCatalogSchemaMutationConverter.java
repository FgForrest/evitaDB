
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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation;

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.MutationCase;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutationConverter;
import io.evitadb.utils.Assert;

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
public class DelegatingLocalCatalogSchemaMutationConverter implements SchemaMutationConverter<LocalCatalogSchemaMutation, GrpcLocalCatalogSchemaMutation> {

	private static final Map<Class<? extends LocalCatalogSchemaMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(20);
		// catalog schema mutations
		TO_GRPC_CONVERTERS.put(ModifyCatalogSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyCatalogSchemaDescriptionMutation((GrpcModifyCatalogSchemaDescriptionMutation) m), new ModifyCatalogSchemaDescriptionMutationConverter()));
		// global attribute schema mutations
		TO_GRPC_CONVERTERS.put(CreateGlobalAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateGlobalAttributeSchemaMutation((GrpcCreateGlobalAttributeSchemaMutation) m), new CreateGlobalAttributeSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDefaultValueMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDefaultValueMutation((GrpcModifyAttributeSchemaDefaultValueMutation) m), new ModifyAttributeSchemaDefaultValueMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDeprecationNoticeMutation((GrpcModifyAttributeSchemaDeprecationNoticeMutation) m), new ModifyAttributeSchemaDeprecationNoticeMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDescriptionMutation((GrpcModifyAttributeSchemaDescriptionMutation) m), new ModifyAttributeSchemaDescriptionMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaNameMutation((GrpcModifyAttributeSchemaNameMutation) m), new ModifyAttributeSchemaNameMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaTypeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaTypeMutation((GrpcModifyAttributeSchemaTypeMutation) m), new ModifyAttributeSchemaTypeMutationConverter()));
		TO_GRPC_CONVERTERS.put(RemoveAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveAttributeSchemaMutation((GrpcRemoveAttributeSchemaMutation) m), new RemoveAttributeSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaFilterableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaFilterableMutation((GrpcSetAttributeSchemaFilterableMutation) m), new SetAttributeSchemaFilterableMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaGloballyUniqueMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaGloballyUniqueMutation((GrpcSetAttributeSchemaGloballyUniqueMutation) m), new SetAttributeSchemaGloballyUniqueMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaLocalizedMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaLocalizedMutation((GrpcSetAttributeSchemaLocalizedMutation) m), new SetAttributeSchemaLocalizedMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaNullableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaNullableMutation((GrpcSetAttributeSchemaNullableMutation) m), new SetAttributeSchemaNullableMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaSortableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaSortableMutation((GrpcSetAttributeSchemaSortableMutation) m), new SetAttributeSchemaSortableMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaUniqueMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaUniqueMutation((GrpcSetAttributeSchemaUniqueMutation) m), new SetAttributeSchemaUniqueMutationConverter()));
		// entity schema mutations
		TO_GRPC_CONVERTERS.put(CreateEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setCreateEntitySchemaMutation((GrpcCreateEntitySchemaMutation) m), new CreateEntitySchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setModifyEntitySchemaMutation((GrpcModifyEntitySchemaMutation) m), new ModifyEntitySchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyEntitySchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyEntitySchemaNameMutation((GrpcModifyEntitySchemaNameMutation) m), new ModifyEntitySchemaNameMutationConverter()));
		TO_GRPC_CONVERTERS.put(RemoveEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveEntitySchemaMutation((GrpcRemoveEntitySchemaMutation) m), new RemoveEntitySchemaMutationConverter()));

		TO_JAVA_CONVERTERS = createHashMap(20);
		// catalog schema mutations
		TO_JAVA_CONVERTERS.put(MODIFYCATALOGSCHEMADESCRIPTIONMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyCatalogSchemaDescriptionMutation, new ModifyCatalogSchemaDescriptionMutationConverter()));
		// global attribute schema mutations
		TO_JAVA_CONVERTERS.put(CREATEGLOBALATTRIBUTESCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getCreateGlobalAttributeSchemaMutation, new CreateGlobalAttributeSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEFAULTVALUEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaDefaultValueMutation, new ModifyAttributeSchemaDefaultValueMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaDeprecationNoticeMutation, new ModifyAttributeSchemaDeprecationNoticeMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADESCRIPTIONMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaDescriptionMutation, new ModifyAttributeSchemaDescriptionMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMANAMEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaNameMutation, new ModifyAttributeSchemaNameMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMATYPEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyAttributeSchemaTypeMutation, new ModifyAttributeSchemaTypeMutationConverter()));
		TO_JAVA_CONVERTERS.put(REMOVEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getRemoveAttributeSchemaMutation, new RemoveAttributeSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAFILTERABLEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaFilterableMutation, new SetAttributeSchemaFilterableMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAGLOBALLYUNIQUEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaGloballyUniqueMutation, new SetAttributeSchemaGloballyUniqueMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMALOCALIZEDMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaLocalizedMutation, new SetAttributeSchemaLocalizedMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMANULLABLEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaNullableMutation, new SetAttributeSchemaNullableMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMASORTABLEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaSortableMutation, new SetAttributeSchemaSortableMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAUNIQUEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getSetAttributeSchemaUniqueMutation, new SetAttributeSchemaUniqueMutationConverter()));
		// entity schema mutations
		TO_JAVA_CONVERTERS.put(CREATEENTITYSCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getCreateEntitySchemaMutation, new CreateEntitySchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYENTITYSCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyEntitySchemaMutation, new ModifyEntitySchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYENTITYSCHEMANAMEMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getModifyEntitySchemaNameMutation, new ModifyEntitySchemaNameMutationConverter()));
		TO_JAVA_CONVERTERS.put(REMOVEENTITYSCHEMAMUTATION, new ToJava(GrpcLocalCatalogSchemaMutation::getRemoveEntitySchemaMutation, new RemoveEntitySchemaMutationConverter()));
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
