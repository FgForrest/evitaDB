
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
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcCreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifySortableAttributeCompoundSchemaNameMutation;
import io.evitadb.externalApi.grpc.generated.GrpcRemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutation.Builder;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutation.MutationCase;
import static io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutation.newBuilder;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of {@link SortableAttributeCompoundSchemaMutation} and
 * {@link GrpcSortableAttributeCompoundSchemaMutation} by delegating each mutation to specific converter.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2022
 */
public class DelegatingSortableAttributeCompoundSchemaMutationConverter
	implements SchemaMutationConverter<SortableAttributeCompoundSchemaMutation, GrpcSortableAttributeCompoundSchemaMutation> {

	private static final Map<Class<? extends SortableAttributeCompoundSchemaMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(20);
		TO_GRPC_CONVERTERS.put(CreateSortableAttributeCompoundSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateSortableAttributeCompoundSchemaMutation((GrpcCreateSortableAttributeCompoundSchemaMutation) m), new CreateSortableAttributeCompoundSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifySortableAttributeCompoundSchemaDeprecationNoticeMutation((GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation) m), new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifySortableAttributeCompoundSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifySortableAttributeCompoundSchemaDescriptionMutation((GrpcModifySortableAttributeCompoundSchemaDescriptionMutation) m), new ModifySortableAttributeCompoundSchemaDescriptionMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifySortableAttributeCompoundSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifySortableAttributeCompoundSchemaNameMutation((GrpcModifySortableAttributeCompoundSchemaNameMutation) m), new ModifySortableAttributeCompoundSchemaNameMutationConverter()));
		TO_GRPC_CONVERTERS.put(RemoveSortableAttributeCompoundSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveSortableAttributeCompoundSchemaMutation((GrpcRemoveSortableAttributeCompoundSchemaMutation) m), new RemoveSortableAttributeCompoundSchemaMutationConverter()));

		TO_JAVA_CONVERTERS = createHashMap(20);
		TO_JAVA_CONVERTERS.put(MutationCase.CREATESORTABLEATTRIBUTECOMPOUNDSCHEMAMUTATION, new ToJava(GrpcSortableAttributeCompoundSchemaMutation::getCreateSortableAttributeCompoundSchemaMutation, new CreateSortableAttributeCompoundSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MutationCase.MODIFYSORTABLEATTRIBUTECOMPOUNDSCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcSortableAttributeCompoundSchemaMutation::getModifySortableAttributeCompoundSchemaDeprecationNoticeMutation, new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter()));
		TO_JAVA_CONVERTERS.put(MutationCase.MODIFYSORTABLEATTRIBUTECOMPOUNDSCHEMADESCRIPTIONMUTATION, new ToJava(GrpcSortableAttributeCompoundSchemaMutation::getModifySortableAttributeCompoundSchemaDescriptionMutation, new ModifySortableAttributeCompoundSchemaDescriptionMutationConverter()));
		TO_JAVA_CONVERTERS.put(MutationCase.MODIFYSORTABLEATTRIBUTECOMPOUNDSCHEMANAMEMUTATION, new ToJava(GrpcSortableAttributeCompoundSchemaMutation::getModifySortableAttributeCompoundSchemaNameMutation, new ModifySortableAttributeCompoundSchemaNameMutationConverter()));
		TO_JAVA_CONVERTERS.put(MutationCase.REMOVESORTABLEATTRIBUTECOMPOUNDSCHEMAMUTATION, new ToJava(GrpcSortableAttributeCompoundSchemaMutation::getRemoveSortableAttributeCompoundSchemaMutation, new RemoveSortableAttributeCompoundSchemaMutationConverter()));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcSortableAttributeCompoundSchemaMutation convert(@Nonnull SortableAttributeCompoundSchemaMutation mutation) {
		final ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final SchemaMutationConverter<SortableAttributeCompoundSchemaMutation, ?> converter =
			(SchemaMutationConverter<SortableAttributeCompoundSchemaMutation, ?>) conversionDescriptor.converter();

		final Builder builder = newBuilder();
		final BiConsumer<Builder, Message> mutationSetter = (BiConsumer<Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public SortableAttributeCompoundSchemaMutation convert(@Nonnull GrpcSortableAttributeCompoundSchemaMutation mutation) {
	    final ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
	    Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

	    final Function<GrpcSortableAttributeCompoundSchemaMutation, Message> extractor =
			 (Function<GrpcSortableAttributeCompoundSchemaMutation, Message>) conversionDescriptor.mutationExtractor();
	    final SchemaMutationConverter<SortableAttributeCompoundSchemaMutation, Message> converter =
			 (SchemaMutationConverter<SortableAttributeCompoundSchemaMutation, Message>) conversionDescriptor.converter();
	    return converter.convert(extractor.apply(mutation));
	}

	private record ToJava(@Nonnull Function<GrpcSortableAttributeCompoundSchemaMutation, ? extends Message> mutationExtractor,
	                      @Nonnull SchemaMutationConverter<? extends SortableAttributeCompoundSchemaMutation, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<Builder, ? extends Message> mutationSetter,
	                      @Nonnull SchemaMutationConverter<? extends SortableAttributeCompoundSchemaMutation, ?> converter) {}
}
