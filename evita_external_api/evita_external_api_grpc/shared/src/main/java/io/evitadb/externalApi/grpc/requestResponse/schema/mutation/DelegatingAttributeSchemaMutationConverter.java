
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
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeSchemaMutation.MutationCase;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute.*;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.externalApi.grpc.generated.GrpcAttributeSchemaMutation.MutationCase.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of {@link AttributeSchemaMutation} and {@link GrpcAttributeSchemaMutation} by delegating
 * each mutation to specific converter.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DelegatingAttributeSchemaMutationConverter implements SchemaMutationConverter<AttributeSchemaMutation, GrpcAttributeSchemaMutation> {
	public static final DelegatingAttributeSchemaMutationConverter INSTANCE = new DelegatingAttributeSchemaMutationConverter();

	private static final Map<Class<? extends AttributeSchemaMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(20);
		TO_GRPC_CONVERTERS.put(CreateAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateAttributeSchemaMutation((GrpcCreateAttributeSchemaMutation) m), CreateAttributeSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDefaultValueMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDefaultValueMutation((GrpcModifyAttributeSchemaDefaultValueMutation) m), ModifyAttributeSchemaDefaultValueMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDeprecationNoticeMutation((GrpcModifyAttributeSchemaDeprecationNoticeMutation) m), ModifyAttributeSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDescriptionMutation((GrpcModifyAttributeSchemaDescriptionMutation) m), ModifyAttributeSchemaDescriptionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaNameMutation((GrpcModifyAttributeSchemaNameMutation) m), ModifyAttributeSchemaNameMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaTypeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaTypeMutation((GrpcModifyAttributeSchemaTypeMutation) m), ModifyAttributeSchemaTypeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveAttributeSchemaMutation((GrpcRemoveAttributeSchemaMutation) m), RemoveAttributeSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaFilterableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaFilterableMutation((GrpcSetAttributeSchemaFilterableMutation) m), SetAttributeSchemaFilterableMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaLocalizedMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaLocalizedMutation((GrpcSetAttributeSchemaLocalizedMutation) m), SetAttributeSchemaLocalizedMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaNullableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaNullableMutation((GrpcSetAttributeSchemaNullableMutation) m), SetAttributeSchemaNullableMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaRepresentativeMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaRepresentativeMutation((GrpcSetAttributeSchemaRepresentativeMutation) m), SetAttributeSchemaRepresentativeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaSortableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaSortableMutation((GrpcSetAttributeSchemaSortableMutation) m), SetAttributeSchemaSortableMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaUniqueMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaUniqueMutation((GrpcSetAttributeSchemaUniqueMutation) m), SetAttributeSchemaUniqueMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(UseGlobalAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setUseGlobalAttributeSchemaMutation((GrpcUseGlobalAttributeSchemaMutation) m), UseGlobalAttributeSchemaMutationConverter.INSTANCE));

		TO_JAVA_CONVERTERS = createHashMap(20);
		TO_JAVA_CONVERTERS.put(CREATEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcAttributeSchemaMutation::getCreateAttributeSchemaMutation, CreateAttributeSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEFAULTVALUEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaDefaultValueMutation, ModifyAttributeSchemaDefaultValueMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaDeprecationNoticeMutation, ModifyAttributeSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADESCRIPTIONMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaDescriptionMutation, ModifyAttributeSchemaDescriptionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMANAMEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaNameMutation, ModifyAttributeSchemaNameMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMATYPEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaTypeMutation, ModifyAttributeSchemaTypeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcAttributeSchemaMutation::getRemoveAttributeSchemaMutation, RemoveAttributeSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAFILTERABLEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaFilterableMutation, SetAttributeSchemaFilterableMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMALOCALIZEDMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaLocalizedMutation, SetAttributeSchemaLocalizedMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMANULLABLEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaNullableMutation, SetAttributeSchemaNullableMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAREPRESENTATIVEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaRepresentativeMutation, SetAttributeSchemaRepresentativeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMASORTABLEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaSortableMutation, SetAttributeSchemaSortableMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAUNIQUEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaUniqueMutation, SetAttributeSchemaUniqueMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(USEGLOBALATTRIBUTESCHEMAMUTATION, new ToJava(GrpcAttributeSchemaMutation::getUseGlobalAttributeSchemaMutation, UseGlobalAttributeSchemaMutationConverter.INSTANCE));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcAttributeSchemaMutation convert(@Nonnull AttributeSchemaMutation mutation) {
		final ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final SchemaMutationConverter<AttributeSchemaMutation, ?> converter =
			(SchemaMutationConverter<AttributeSchemaMutation, ?>) conversionDescriptor.converter();

		final GrpcAttributeSchemaMutation.Builder builder = GrpcAttributeSchemaMutation.newBuilder();
		final BiConsumer<GrpcAttributeSchemaMutation.Builder, Message> mutationSetter = (BiConsumer<GrpcAttributeSchemaMutation.Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public AttributeSchemaMutation convert(@Nonnull GrpcAttributeSchemaMutation mutation) {
	    final ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
	    Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

	    final Function<GrpcAttributeSchemaMutation, Message> extractor =
			 (Function<GrpcAttributeSchemaMutation, Message>) conversionDescriptor.mutationExtractor();
	    final SchemaMutationConverter<AttributeSchemaMutation, Message> converter =
			 (SchemaMutationConverter<AttributeSchemaMutation, Message>) conversionDescriptor.converter();
	    return converter.convert(extractor.apply(mutation));
	}

	private record ToJava(@Nonnull Function<GrpcAttributeSchemaMutation, ? extends Message> mutationExtractor,
	                      @Nonnull SchemaMutationConverter<? extends AttributeSchemaMutation, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<GrpcAttributeSchemaMutation.Builder, ? extends Message> mutationSetter,
	                      @Nonnull SchemaMutationConverter<? extends AttributeSchemaMutation, ?> converter) {}
}
