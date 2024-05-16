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
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEntityMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEntityMutation.MutationCase;
import io.evitadb.externalApi.grpc.generated.GrpcEntityRemoveMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEntityUpsertMutation;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.externalApi.grpc.generated.GrpcEntityMutation.MutationCase.ENTITYREMOVEMUTATION;
import static io.evitadb.externalApi.grpc.generated.GrpcEntityMutation.MutationCase.ENTITYUPSERTMUTATION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of {@link EntityMutation} and {@link GrpcEntityMutation} by delegating
 * each mutation to specific converter.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DelegatingEntityMutationConverter implements EntityMutationConverter<EntityMutation, GrpcEntityMutation> {

	private static final Map<Class<? extends EntityMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(5);
		TO_GRPC_CONVERTERS.put(EntityUpsertMutation.class, new ToGrpc((b, m) -> b.setEntityUpsertMutation((GrpcEntityUpsertMutation) m), new EntityUpsertMutationConverter()));
		TO_GRPC_CONVERTERS.put(EntityRemoveMutation.class, new ToGrpc((b, m) -> b.setEntityRemoveMutation((GrpcEntityRemoveMutation) m), new EntityRemoveMutationConverter()));

		TO_JAVA_CONVERTERS = createHashMap(5);
		TO_JAVA_CONVERTERS.put(ENTITYUPSERTMUTATION, new ToJava(GrpcEntityMutation::getEntityUpsertMutation, new EntityUpsertMutationConverter()));
		TO_JAVA_CONVERTERS.put(ENTITYREMOVEMUTATION, new ToJava(GrpcEntityMutation::getEntityRemoveMutation, new EntityRemoveMutationConverter()));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcEntityMutation convert(@Nonnull EntityMutation mutation) {
		final ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final EntityMutationConverter<EntityMutation, ?> converter =
			(EntityMutationConverter<EntityMutation, ?>) conversionDescriptor.converter();

		final GrpcEntityMutation.Builder builder = GrpcEntityMutation.newBuilder();
		final BiConsumer<GrpcEntityMutation.Builder, Message> mutationSetter = (BiConsumer<GrpcEntityMutation.Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public EntityMutation convert(@Nonnull GrpcEntityMutation mutation) {
	    final ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
	    Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

	    final Function<GrpcEntityMutation, Message> extractor =
			 (Function<GrpcEntityMutation, Message>) conversionDescriptor.mutationExtractor();
	    final EntityMutationConverter<EntityMutation, Message> converter =
			 (EntityMutationConverter<EntityMutation, Message>) conversionDescriptor.converter();
	    return converter.convert(extractor.apply(mutation));
	}

	private record ToJava(@Nonnull Function<GrpcEntityMutation, ? extends Message> mutationExtractor,
	                      @Nonnull EntityMutationConverter<? extends EntityMutation, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<GrpcEntityMutation.Builder, ? extends Message> mutationSetter,
	                      @Nonnull EntityMutationConverter<? extends EntityMutation, ?> converter) {}
}
