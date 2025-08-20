
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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation;

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcEngineMutation.MutationCase;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.MutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.DuplicateCatalogMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.MakeCatalogAliveMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.SetCatalogStateMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.TransactionMutationConverter;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.externalApi.grpc.generated.GrpcEngineMutation.MutationCase.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of {@link EngineMutation} and {@link GrpcEngineMutation} by delegating
 * each mutation to specific converter.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DelegatingEngineMutationConverter {
	public static final DelegatingEngineMutationConverter INSTANCE = new DelegatingEngineMutationConverter();

	private static final Map<Class<? extends EngineMutation<?>>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(32);
		TO_GRPC_CONVERTERS.put(CreateCatalogSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateCatalogSchemaMutation((GrpcCreateCatalogSchemaMutation) m), CreateCatalogSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyCatalogSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyCatalogSchemaNameMutation((GrpcModifyCatalogSchemaNameMutation) m), ModifyCatalogSchemaNameMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyCatalogSchemaMutation.class, new ToGrpc((b, m) -> b.setModifyCatalogSchemaMutation((GrpcModifyCatalogSchemaMutation) m), ModifyCatalogSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(MakeCatalogAliveMutation.class, new ToGrpc((b, m) -> b.setMakeCatalogAliveMutation((GrpcMakeCatalogAliveMutation) m), MakeCatalogAliveMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveCatalogSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveCatalogSchemaMutation((GrpcRemoveCatalogSchemaMutation) m), RemoveCatalogSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(TransactionMutation.class, new ToGrpc((b, m) -> b.setTransactionMutation((GrpcTransactionMutation) m), TransactionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(DuplicateCatalogMutation.class, new ToGrpc((b, m) -> b.setDuplicateCatalogMutation((GrpcDuplicateCatalogMutation) m), DuplicateCatalogMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetCatalogMutabilityMutation.class, new ToGrpc((b, m) -> b.setSetCatalogMutabilityMutation((GrpcSetCatalogMutabilityMutation) m), SetCatalogMutabilityMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetCatalogStateMutation.class, new ToGrpc((b, m) -> b.setSetCatalogStateMutation((GrpcSetCatalogStateMutation) m), SetCatalogStateMutationConverter.INSTANCE));

		TO_JAVA_CONVERTERS = createHashMap(32);
		TO_JAVA_CONVERTERS.put(CREATECATALOGSCHEMAMUTATION, new ToJava(GrpcEngineMutation::getCreateCatalogSchemaMutation, CreateCatalogSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYCATALOGSCHEMANAMEMUTATION, new ToJava(GrpcEngineMutation::getModifyCatalogSchemaNameMutation, ModifyCatalogSchemaNameMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYCATALOGSCHEMAMUTATION, new ToJava(GrpcEngineMutation::getModifyCatalogSchemaMutation, ModifyCatalogSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MAKECATALOGALIVEMUTATION, new ToJava(GrpcEngineMutation::getMakeCatalogAliveMutation, MakeCatalogAliveMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVECATALOGSCHEMAMUTATION, new ToJava(GrpcEngineMutation::getRemoveCatalogSchemaMutation, RemoveCatalogSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(TRANSACTIONMUTATION, new ToJava(GrpcEngineMutation::getTransactionMutation, TransactionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(DUPLICATECATALOGMUTATION, new ToJava(GrpcEngineMutation::getDuplicateCatalogMutation, DuplicateCatalogMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETCATALOGMUTABILITYMUTATION, new ToJava(GrpcEngineMutation::getSetCatalogMutabilityMutation, SetCatalogMutabilityMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETCATALOGSTATEMUTATION, new ToJava(GrpcEngineMutation::getSetCatalogStateMutation, SetCatalogStateMutationConverter.INSTANCE));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcEngineMutation convert(@Nonnull EngineMutation<?> mutation) {
		final ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final MutationConverter<EngineMutation<?>, ?> converter =
			(MutationConverter<EngineMutation<?>, ?>) conversionDescriptor.converter();

		final GrpcEngineMutation.Builder builder = GrpcEngineMutation.newBuilder();
		final BiConsumer<GrpcEngineMutation.Builder, Message> mutationSetter = (BiConsumer<GrpcEngineMutation.Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nullable
	public EngineMutation<?> convert(@Nonnull GrpcEngineMutation mutation) {
		if (mutation.getMutationCase() == MutationCase.MUTATION_NOT_SET) {
			return null;
		} else {
			final ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
			Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

			final Function<GrpcEngineMutation, Message> extractor =
				(Function<GrpcEngineMutation, Message>) conversionDescriptor.mutationExtractor();
			final MutationConverter<EngineMutation<?>, Message> converter =
				(MutationConverter<EngineMutation<?>, Message>) conversionDescriptor.converter();
			return converter.convert(extractor.apply(mutation));
		}
	}

	private record ToJava(@Nonnull Function<GrpcEngineMutation, ? extends Message> mutationExtractor,
	                      @Nonnull MutationConverter<? extends EngineMutation<?>, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<GrpcEngineMutation.Builder, ? extends Message> mutationSetter,
	                      @Nonnull MutationConverter<? extends EngineMutation<?>, ?> converter) {}
}
