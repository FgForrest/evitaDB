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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation;

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.externalApi.grpc.generated.GrpcApplyDeltaAttributeMutation;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeMutation;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeMutation.MutationCase;
import io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation;
import io.evitadb.externalApi.grpc.generated.GrpcUpsertAttributeMutation;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute.RemoveAttributeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute.UpsertAttributeMutationConverter;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.externalApi.grpc.generated.GrpcAttributeMutation.MutationCase.APPLYDELTAATTRIBUTEMUTATION;
import static io.evitadb.externalApi.grpc.generated.GrpcAttributeMutation.MutationCase.REMOVEATTRIBUTEMUTATION;
import static io.evitadb.externalApi.grpc.generated.GrpcAttributeMutation.MutationCase.UPSERTATTRIBUTEMUTATION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of {@link AttributeMutation} and {@link GrpcAttributeMutation} by delegating
 * each mutation to specific converter.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DelegatingAttributeMutationConverter implements LocalMutationConverter<AttributeMutation, GrpcAttributeMutation> {

	private static final Map<Class<? extends AttributeMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(5);
		TO_GRPC_CONVERTERS.put(ApplyDeltaAttributeMutation.class, new ToGrpc((b, m) -> b.setApplyDeltaAttributeMutation((GrpcApplyDeltaAttributeMutation) m), new ApplyDeltaAttributeMutationConverter()));
		TO_GRPC_CONVERTERS.put(RemoveAttributeMutation.class, new ToGrpc((b, m) -> b.setRemoveAttributeMutation((GrpcRemoveAttributeMutation) m), new RemoveAttributeMutationConverter()));
		TO_GRPC_CONVERTERS.put(UpsertAttributeMutation.class, new ToGrpc((b, m) -> b.setUpsertAttributeMutation((GrpcUpsertAttributeMutation) m), new UpsertAttributeMutationConverter()));

		TO_JAVA_CONVERTERS = createHashMap(5);
		TO_JAVA_CONVERTERS.put(APPLYDELTAATTRIBUTEMUTATION, new ToJava(GrpcAttributeMutation::getApplyDeltaAttributeMutation, new ApplyDeltaAttributeMutationConverter()));
		TO_JAVA_CONVERTERS.put(REMOVEATTRIBUTEMUTATION, new ToJava(GrpcAttributeMutation::getRemoveAttributeMutation, new RemoveAttributeMutationConverter()));
		TO_JAVA_CONVERTERS.put(UPSERTATTRIBUTEMUTATION, new ToJava(GrpcAttributeMutation::getUpsertAttributeMutation, new UpsertAttributeMutationConverter()));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcAttributeMutation convert(@Nonnull AttributeMutation mutation) {
		final ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final LocalMutationConverter<AttributeMutation, ?> converter =
			(LocalMutationConverter<AttributeMutation, ?>) conversionDescriptor.converter();

		final GrpcAttributeMutation.Builder builder = GrpcAttributeMutation.newBuilder();
		final BiConsumer<GrpcAttributeMutation.Builder, Message> mutationSetter = (BiConsumer<GrpcAttributeMutation.Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public AttributeMutation convert(@Nonnull GrpcAttributeMutation mutation) {
	    final ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
	    Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

	    final Function<GrpcAttributeMutation, Message> extractor =
			 (Function<GrpcAttributeMutation, Message>) conversionDescriptor.mutationExtractor();
	    final LocalMutationConverter<AttributeMutation, Message> converter =
			 (LocalMutationConverter<AttributeMutation, Message>) conversionDescriptor.converter();
	    return converter.convert(extractor.apply(mutation));
	}

	private record ToJava(@Nonnull Function<GrpcAttributeMutation, ? extends Message> mutationExtractor,
	                      @Nonnull LocalMutationConverter<? extends AttributeMutation, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<GrpcAttributeMutation.Builder, ? extends Message> mutationSetter,
	                      @Nonnull LocalMutationConverter<? extends AttributeMutation, ?> converter) {}
}
