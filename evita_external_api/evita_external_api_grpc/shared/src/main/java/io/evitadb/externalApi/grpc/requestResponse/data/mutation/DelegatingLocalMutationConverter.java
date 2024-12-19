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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation;

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcLocalMutation.MutationCase;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute.RemoveAttributeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute.UpsertAttributeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.entity.RemoveParentMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.entity.SetEntityScopeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.entity.SetParentMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.price.RemovePriceMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.price.UpsertPriceMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.reference.InsertReferenceMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.reference.ReferenceAttributeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.reference.RemoveReferenceGroupMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.reference.RemoveReferenceMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.reference.SetReferenceGroupMutationConverter;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of {@link LocalMutation} and {@link GrpcLocalMutation} by delegating
 * each mutation to specific converter.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DelegatingLocalMutationConverter implements LocalMutationConverter<LocalMutation<?, ?>, GrpcLocalMutation> {
	public static final DelegatingLocalMutationConverter INSTANCE = new DelegatingLocalMutationConverter();

	@SuppressWarnings("rawtypes")
	private static final Map<Class<? extends LocalMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(20);
		TO_GRPC_CONVERTERS.put(ApplyDeltaAttributeMutation.class, new ToGrpc((b, m) -> b.setApplyDeltaAttributeMutation((GrpcApplyDeltaAttributeMutation) m), ApplyDeltaAttributeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(UpsertAttributeMutation.class, new ToGrpc((b, m) -> b.setUpsertAttributeMutation((GrpcUpsertAttributeMutation) m), UpsertAttributeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveAttributeMutation.class, new ToGrpc((b, m) -> b.setRemoveAttributeMutation((GrpcRemoveAttributeMutation) m), RemoveAttributeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(UpsertAssociatedDataMutation.class, new ToGrpc((b, m) -> b.setUpsertAssociatedDataMutation((GrpcUpsertAssociatedDataMutation) m), UpsertAssociatedDataMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveAssociatedDataMutation.class, new ToGrpc((b, m) -> b.setRemoveAssociatedDataMutation((GrpcRemoveAssociatedDataMutation) m), RemoveAssociatedDataMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(UpsertPriceMutation.class, new ToGrpc((b, m) -> b.setUpsertPriceMutation((GrpcUpsertPriceMutation) m), UpsertPriceMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemovePriceMutation.class, new ToGrpc((b, m) -> b.setRemovePriceMutation((GrpcRemovePriceMutation) m), RemovePriceMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetPriceInnerRecordHandlingMutation.class, new ToGrpc((b, m) -> b.setSetPriceInnerRecordHandlingMutation((GrpcSetPriceInnerRecordHandlingMutation) m), SetPriceInnerRecordHandlingMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetParentMutation.class, new ToGrpc((b, m) -> b.setSetParentMutation((GrpcSetParentMutation) m), SetParentMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveParentMutation.class, new ToGrpc((b, m) -> b.setRemoveParentMutation((GrpcRemoveParentMutation) m), RemoveParentMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(InsertReferenceMutation.class, new ToGrpc((b, m) -> b.setInsertReferenceMutation((GrpcInsertReferenceMutation) m), InsertReferenceMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveReferenceMutation.class, new ToGrpc((b, m) -> b.setRemoveReferenceMutation((GrpcRemoveReferenceMutation) m), RemoveReferenceMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetReferenceGroupMutation.class, new ToGrpc((b, m) -> b.setSetReferenceGroupMutation((GrpcSetReferenceGroupMutation) m), SetReferenceGroupMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveReferenceGroupMutation.class, new ToGrpc((b, m) -> b.setRemoveReferenceGroupMutation((GrpcRemoveReferenceGroupMutation) m), RemoveReferenceGroupMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ReferenceAttributeMutation.class, new ToGrpc((b, m) -> b.setReferenceAttributeMutation((GrpcReferenceAttributeMutation) m), ReferenceAttributeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetEntityScopeMutation.class, new ToGrpc((b, m) -> b.setSetEntityScopeMutation((GrpcSetEntityScopeMutation) m), SetEntityScopeMutationConverter.INSTANCE));

		TO_JAVA_CONVERTERS = createHashMap(20);
		TO_JAVA_CONVERTERS.put(MutationCase.APPLYDELTAATTRIBUTEMUTATION, new ToJava(GrpcLocalMutation::getApplyDeltaAttributeMutation, ApplyDeltaAttributeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.UPSERTATTRIBUTEMUTATION, new ToJava(GrpcLocalMutation::getUpsertAttributeMutation, UpsertAttributeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.REMOVEATTRIBUTEMUTATION, new ToJava(GrpcLocalMutation::getRemoveAttributeMutation, RemoveAttributeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.UPSERTASSOCIATEDDATAMUTATION, new ToJava(GrpcLocalMutation::getUpsertAssociatedDataMutation, UpsertAssociatedDataMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.REMOVEASSOCIATEDDATAMUTATION, new ToJava(GrpcLocalMutation::getRemoveAssociatedDataMutation, RemoveAssociatedDataMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.UPSERTPRICEMUTATION, new ToJava(GrpcLocalMutation::getUpsertPriceMutation, UpsertPriceMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.REMOVEPRICEMUTATION, new ToJava(GrpcLocalMutation::getRemovePriceMutation, RemovePriceMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.SETPRICEINNERRECORDHANDLINGMUTATION, new ToJava(GrpcLocalMutation::getSetPriceInnerRecordHandlingMutation, SetPriceInnerRecordHandlingMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.SETPARENTMUTATION, new ToJava(GrpcLocalMutation::getSetParentMutation, SetParentMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.REMOVEPARENTMUTATION, new ToJava(GrpcLocalMutation::getRemoveParentMutation, RemoveParentMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.INSERTREFERENCEMUTATION, new ToJava(GrpcLocalMutation::getInsertReferenceMutation, InsertReferenceMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.REMOVEREFERENCEMUTATION, new ToJava(GrpcLocalMutation::getRemoveReferenceMutation, RemoveReferenceMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.SETREFERENCEGROUPMUTATION, new ToJava(GrpcLocalMutation::getSetReferenceGroupMutation, SetReferenceGroupMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.REMOVEREFERENCEGROUPMUTATION, new ToJava(GrpcLocalMutation::getRemoveReferenceGroupMutation, RemoveReferenceGroupMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.REFERENCEATTRIBUTEMUTATION, new ToJava(GrpcLocalMutation::getReferenceAttributeMutation, ReferenceAttributeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MutationCase.SETENTITYSCOPEMUTATION, new ToJava(GrpcLocalMutation::getSetEntityScopeMutation, SetEntityScopeMutationConverter.INSTANCE));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcLocalMutation convert(@Nonnull LocalMutation<?, ?> mutation) {
		final ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final LocalMutationConverter<LocalMutation<?, ?>, ?> converter =
			(LocalMutationConverter<LocalMutation<?, ?>, ?>) conversionDescriptor.converter();

		final GrpcLocalMutation.Builder builder = GrpcLocalMutation.newBuilder();
		final BiConsumer<GrpcLocalMutation.Builder, Message> mutationSetter = (BiConsumer<GrpcLocalMutation.Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public LocalMutation<?, ?> convert(@Nonnull GrpcLocalMutation mutation) {
	    final ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
	    Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

	    final Function<GrpcLocalMutation, Message> extractor =
			 (Function<GrpcLocalMutation, Message>) conversionDescriptor.mutationExtractor();
	    final LocalMutationConverter<LocalMutation<?, ?>, Message> converter =
			 (LocalMutationConverter<LocalMutation<?, ?>, Message>) conversionDescriptor.converter();
	    return converter.convert(extractor.apply(mutation));
	}

	private record ToJava(@Nonnull Function<GrpcLocalMutation, ? extends Message> mutationExtractor,
	                      @Nonnull LocalMutationConverter<? extends LocalMutation<?, ?>, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<GrpcLocalMutation.Builder, ? extends Message> mutationSetter,
	                      @Nonnull LocalMutationConverter<? extends LocalMutation<?, ?>, ?> converter) {}
}
