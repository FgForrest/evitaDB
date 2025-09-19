/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.externalApi.grpc.generated.GrpcInfrastructureMutation;
import io.evitadb.externalApi.grpc.generated.GrpcInfrastructureMutation.MutationCase;
import io.evitadb.externalApi.grpc.generated.GrpcTransactionMutation;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.MutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.TransactionMutationConverter;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.externalApi.grpc.generated.GrpcInfrastructureMutation.MutationCase.TRANSACTIONMUTATION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of infrastructure {@link Mutation} and {@link GrpcInfrastructureMutation} by
 * delegating each mutation to specific converter. Currently only {@link TransactionMutation} is supported.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class DelegatingInfrastructureMutationConverter {
	public static final DelegatingInfrastructureMutationConverter INSTANCE = new DelegatingInfrastructureMutationConverter();

	private static final Map<Class<? extends Mutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(4);
		TO_GRPC_CONVERTERS.put(TransactionMutation.class, new DelegatingInfrastructureMutationConverter.ToGrpc((b, m) -> b.setTransactionMutation((GrpcTransactionMutation) m), TransactionMutationConverter.INSTANCE));

		TO_JAVA_CONVERTERS = createHashMap(4);
		TO_JAVA_CONVERTERS.put(TRANSACTIONMUTATION, new DelegatingInfrastructureMutationConverter.ToJava(GrpcInfrastructureMutation::getTransactionMutation, TransactionMutationConverter.INSTANCE));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcInfrastructureMutation convert(@Nonnull Mutation mutation) {
		final DelegatingInfrastructureMutationConverter.ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final MutationConverter<Mutation, ?> converter = (MutationConverter<Mutation, ?>) conversionDescriptor.converter();

		final GrpcInfrastructureMutation.Builder builder = GrpcInfrastructureMutation.newBuilder();
		final BiConsumer<GrpcInfrastructureMutation.Builder, Message> mutationSetter = (BiConsumer<GrpcInfrastructureMutation.Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public Mutation convert(@Nonnull GrpcInfrastructureMutation mutation) {
		final DelegatingInfrastructureMutationConverter.ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

		final Function<GrpcInfrastructureMutation, Message> extractor =
			(Function<GrpcInfrastructureMutation, Message>) conversionDescriptor.mutationExtractor();
		final MutationConverter<Mutation, Message> converter =
			(MutationConverter<Mutation, Message>) conversionDescriptor.converter();
		return converter.convert(extractor.apply(mutation));
	}

	private record ToJava(@Nonnull Function<GrpcInfrastructureMutation, ? extends Message> mutationExtractor,
	                      @Nonnull MutationConverter<? extends Mutation, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<GrpcInfrastructureMutation.Builder, ? extends Message> mutationSetter,
	                      @Nonnull MutationConverter<? extends Mutation, ?> converter) {}

}
