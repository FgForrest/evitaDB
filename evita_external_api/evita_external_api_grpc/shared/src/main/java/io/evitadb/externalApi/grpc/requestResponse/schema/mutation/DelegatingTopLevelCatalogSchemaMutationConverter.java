
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
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcCreateCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyCatalogSchemaNameMutation;
import io.evitadb.externalApi.grpc.generated.GrpcRemoveCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcTopLevelCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcTopLevelCatalogSchemaMutation.MutationCase;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.CreateCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaNameMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.RemoveCatalogSchemaMutationConverter;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.externalApi.grpc.generated.GrpcTopLevelCatalogSchemaMutation.MutationCase.CREATECATALOGSCHEMAMUTATION;
import static io.evitadb.externalApi.grpc.generated.GrpcTopLevelCatalogSchemaMutation.MutationCase.MODIFYCATALOGSCHEMANAMEMUTATION;
import static io.evitadb.externalApi.grpc.generated.GrpcTopLevelCatalogSchemaMutation.MutationCase.REMOVECATALOGSCHEMAMUTATION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of {@link TopLevelCatalogSchemaMutation} and {@link GrpcTopLevelCatalogSchemaMutation} by delegating
 * each mutation to specific converter.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class DelegatingTopLevelCatalogSchemaMutationConverter implements SchemaMutationConverter<TopLevelCatalogSchemaMutation, GrpcTopLevelCatalogSchemaMutation> {

	private static final Map<Class<? extends TopLevelCatalogSchemaMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(5);
		TO_GRPC_CONVERTERS.put(CreateCatalogSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateCatalogSchemaMutation((GrpcCreateCatalogSchemaMutation) m), new CreateCatalogSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyCatalogSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyCatalogSchemaNameMutation((GrpcModifyCatalogSchemaNameMutation) m), new ModifyCatalogSchemaNameMutationConverter()));
		TO_GRPC_CONVERTERS.put(RemoveCatalogSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveCatalogSchemaMutation((GrpcRemoveCatalogSchemaMutation) m), new RemoveCatalogSchemaMutationConverter()));

		TO_JAVA_CONVERTERS = createHashMap(5);
		TO_JAVA_CONVERTERS.put(CREATECATALOGSCHEMAMUTATION, new ToJava(GrpcTopLevelCatalogSchemaMutation::getCreateCatalogSchemaMutation, new CreateCatalogSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYCATALOGSCHEMANAMEMUTATION, new ToJava(GrpcTopLevelCatalogSchemaMutation::getModifyCatalogSchemaNameMutation, new ModifyCatalogSchemaNameMutationConverter()));
		TO_JAVA_CONVERTERS.put(REMOVECATALOGSCHEMAMUTATION, new ToJava(GrpcTopLevelCatalogSchemaMutation::getRemoveCatalogSchemaMutation, new RemoveCatalogSchemaMutationConverter()));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcTopLevelCatalogSchemaMutation convert(@Nonnull TopLevelCatalogSchemaMutation mutation) {
		final ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final SchemaMutationConverter<TopLevelCatalogSchemaMutation, ?> converter =
			(SchemaMutationConverter<TopLevelCatalogSchemaMutation, ?>) conversionDescriptor.converter();

		final GrpcTopLevelCatalogSchemaMutation.Builder builder = GrpcTopLevelCatalogSchemaMutation.newBuilder();
		final BiConsumer<GrpcTopLevelCatalogSchemaMutation.Builder, Message> mutationSetter = (BiConsumer<GrpcTopLevelCatalogSchemaMutation.Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public TopLevelCatalogSchemaMutation convert(@Nonnull GrpcTopLevelCatalogSchemaMutation mutation) {
		final ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

		final Function<GrpcTopLevelCatalogSchemaMutation, Message> extractor =
			(Function<GrpcTopLevelCatalogSchemaMutation, Message>) conversionDescriptor.mutationExtractor();
		final SchemaMutationConverter<TopLevelCatalogSchemaMutation, Message> converter =
			(SchemaMutationConverter<TopLevelCatalogSchemaMutation, Message>) conversionDescriptor.converter();
		return converter.convert(extractor.apply(mutation));
	}

	private record ToJava(@Nonnull Function<GrpcTopLevelCatalogSchemaMutation, ? extends Message> mutationExtractor,
	                      @Nonnull SchemaMutationConverter<? extends TopLevelCatalogSchemaMutation, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<GrpcTopLevelCatalogSchemaMutation.Builder, ? extends Message> mutationSetter,
	                      @Nonnull SchemaMutationConverter<? extends TopLevelCatalogSchemaMutation, ?> converter) {}
}
