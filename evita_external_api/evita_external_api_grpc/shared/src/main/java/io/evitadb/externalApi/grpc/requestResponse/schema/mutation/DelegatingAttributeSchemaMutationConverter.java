
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
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeSchemaMutation.MutationCase;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute.*;
import io.evitadb.utils.Assert;

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
public class DelegatingAttributeSchemaMutationConverter implements SchemaMutationConverter<AttributeSchemaMutation, GrpcAttributeSchemaMutation> {

	private static final Map<Class<? extends AttributeSchemaMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(20);
		TO_GRPC_CONVERTERS.put(CreateAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateAttributeSchemaMutation((GrpcCreateAttributeSchemaMutation) m), new CreateAttributeSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDefaultValueMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDefaultValueMutation((GrpcModifyAttributeSchemaDefaultValueMutation) m), new ModifyAttributeSchemaDefaultValueMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDeprecationNoticeMutation((GrpcModifyAttributeSchemaDeprecationNoticeMutation) m), new ModifyAttributeSchemaDeprecationNoticeMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDescriptionMutation((GrpcModifyAttributeSchemaDescriptionMutation) m), new ModifyAttributeSchemaDescriptionMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaNameMutation((GrpcModifyAttributeSchemaNameMutation) m), new ModifyAttributeSchemaNameMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaTypeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaTypeMutation((GrpcModifyAttributeSchemaTypeMutation) m), new ModifyAttributeSchemaTypeMutationConverter()));
		TO_GRPC_CONVERTERS.put(RemoveAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveAttributeSchemaMutation((GrpcRemoveAttributeSchemaMutation) m), new RemoveAttributeSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaFilterableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaFilterableMutation((GrpcSetAttributeSchemaFilterableMutation) m), new SetAttributeSchemaFilterableMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaLocalizedMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaLocalizedMutation((GrpcSetAttributeSchemaLocalizedMutation) m), new SetAttributeSchemaLocalizedMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaNullableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaNullableMutation((GrpcSetAttributeSchemaNullableMutation) m), new SetAttributeSchemaNullableMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaSortableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaSortableMutation((GrpcSetAttributeSchemaSortableMutation) m), new SetAttributeSchemaSortableMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaUniqueMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaUniqueMutation((GrpcSetAttributeSchemaUniqueMutation) m), new SetAttributeSchemaUniqueMutationConverter()));
		TO_GRPC_CONVERTERS.put(UseGlobalAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setUseGlobalAttributeSchemaMutation((GrpcUseGlobalAttributeSchemaMutation) m), new UseGlobalAttributeSchemaMutationConverter()));

		TO_JAVA_CONVERTERS = createHashMap(20);
		TO_JAVA_CONVERTERS.put(CREATEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcAttributeSchemaMutation::getCreateAttributeSchemaMutation, new CreateAttributeSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEFAULTVALUEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaDefaultValueMutation, new ModifyAttributeSchemaDefaultValueMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaDeprecationNoticeMutation, new ModifyAttributeSchemaDeprecationNoticeMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADESCRIPTIONMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaDescriptionMutation, new ModifyAttributeSchemaDescriptionMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMANAMEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaNameMutation, new ModifyAttributeSchemaNameMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMATYPEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getModifyAttributeSchemaTypeMutation, new ModifyAttributeSchemaTypeMutationConverter()));
		TO_JAVA_CONVERTERS.put(REMOVEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcAttributeSchemaMutation::getRemoveAttributeSchemaMutation, new RemoveAttributeSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAFILTERABLEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaFilterableMutation, new SetAttributeSchemaFilterableMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMALOCALIZEDMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaLocalizedMutation, new SetAttributeSchemaLocalizedMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMANULLABLEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaNullableMutation, new SetAttributeSchemaNullableMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMASORTABLEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaSortableMutation, new SetAttributeSchemaSortableMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAUNIQUEMUTATION, new ToJava(GrpcAttributeSchemaMutation::getSetAttributeSchemaUniqueMutation, new SetAttributeSchemaUniqueMutationConverter()));
		TO_JAVA_CONVERTERS.put(USEGLOBALATTRIBUTESCHEMAMUTATION, new ToJava(GrpcAttributeSchemaMutation::getUseGlobalAttributeSchemaMutation, new UseGlobalAttributeSchemaMutationConverter()));
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
