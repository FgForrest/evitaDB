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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;

import com.google.protobuf.BoolValue;
import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcCreateReflectedReferenceSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toAttributeInheritanceBehavior;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCardinality;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcAttributeInheritanceBehavior;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCardinality;

/**
 * Converts between {@link CreateReflectedReferenceSchemaMutation} and {@link GrpcCreateReflectedReferenceSchemaMutation} in both directions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateReflectedReferenceSchemaMutationConverter implements SchemaMutationConverter<CreateReflectedReferenceSchemaMutation, GrpcCreateReflectedReferenceSchemaMutation> {
	public static final CreateReflectedReferenceSchemaMutationConverter INSTANCE = new CreateReflectedReferenceSchemaMutationConverter();

	@Nonnull
	public CreateReflectedReferenceSchemaMutation convert(@Nonnull GrpcCreateReflectedReferenceSchemaMutation mutation) {
		return new CreateReflectedReferenceSchemaMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null,
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null,
			toCardinality(mutation.getCardinality()),
			mutation.getReferencedEntityType(),
			mutation.getReflectedReferenceName(),
			mutation.hasFilterable() ? mutation.getFilterable().getValue() : null,
			mutation.hasFaceted() ? mutation.getFaceted().getValue() : null,
			toAttributeInheritanceBehavior(mutation.getAttributeInheritanceBehavior()),
			mutation.getAttributeInheritanceFilterList().toArray(String[]::new)
		);
	}

	@Nonnull
	public GrpcCreateReflectedReferenceSchemaMutation convert(@Nonnull CreateReflectedReferenceSchemaMutation mutation) {
		final GrpcCreateReflectedReferenceSchemaMutation.Builder builder = GrpcCreateReflectedReferenceSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.setCardinality(toGrpcCardinality(mutation.getCardinality()))
			.setReferencedEntityType(mutation.getReferencedEntityType())
			.setReflectedReferenceName(mutation.getReflectedReferenceName())
			.setAttributeInheritanceBehavior(toGrpcAttributeInheritanceBehavior(mutation.getAttributesInheritanceBehavior()));

		if (mutation.getDescription() != null) {
			builder.setDescription(StringValue.of(mutation.getDescription()));
		}
		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}
		if (mutation.getIndexed() != null) {
			builder.setFilterable(BoolValue.newBuilder().setValue(mutation.getIndexed()).build());
		}
		if (mutation.getFaceted() != null) {
			builder.setFaceted(BoolValue.newBuilder().setValue(mutation.getFaceted()).build());
		}
		for (String attribute : mutation.getAttributeInheritanceFilter()) {
			builder.addAttributeInheritanceFilter(attribute);
		}

		return builder.build();
	}
}
