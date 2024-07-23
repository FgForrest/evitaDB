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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound;

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcCreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.EntitySchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Converts between {@link CreateSortableAttributeCompoundSchemaMutation} and {@link GrpcCreateSortableAttributeCompoundSchemaMutation} in both directions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2023
 */
public class CreateSortableAttributeCompoundSchemaMutationConverter implements SchemaMutationConverter<CreateSortableAttributeCompoundSchemaMutation, GrpcCreateSortableAttributeCompoundSchemaMutation> {

	@Nonnull
	public CreateSortableAttributeCompoundSchemaMutation convert(@Nonnull GrpcCreateSortableAttributeCompoundSchemaMutation mutation) {
		return new CreateSortableAttributeCompoundSchemaMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null,
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null,
			EntitySchemaConverter.toAttributeElement(mutation.getAttributeElementsList()).toArray(AttributeElement[]::new)
		);
	}

	@Nonnull
	public GrpcCreateSortableAttributeCompoundSchemaMutation convert(@Nonnull CreateSortableAttributeCompoundSchemaMutation mutation) {
		final GrpcCreateSortableAttributeCompoundSchemaMutation.Builder builder = GrpcCreateSortableAttributeCompoundSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.addAllAttributeElements(EntitySchemaConverter.toGrpcAttributeElement(Arrays.asList(mutation.getAttributeElements())));

		if (mutation.getDescription() != null) {
			builder.setDescription(StringValue.of(mutation.getDescription()));
		}
		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}

		return builder.build();
	}
}
