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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReflectedReferenceAttributeInheritanceSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyReflectedReferenceAttributeInheritanceSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toAttributeInheritanceBehavior;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcAttributeInheritanceBehavior;

/**
 * Converts between {@link ModifyReflectedReferenceAttributeInheritanceSchemaMutation} and {@link GrpcModifyReflectedReferenceAttributeInheritanceSchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifyReflectedReferenceAttributeInheritanceSchemaMutationConverter implements SchemaMutationConverter<ModifyReflectedReferenceAttributeInheritanceSchemaMutation, GrpcModifyReflectedReferenceAttributeInheritanceSchemaMutation> {
	public static final ModifyReflectedReferenceAttributeInheritanceSchemaMutationConverter INSTANCE = new ModifyReflectedReferenceAttributeInheritanceSchemaMutationConverter();

	@Nonnull
	public ModifyReflectedReferenceAttributeInheritanceSchemaMutation convert(@Nonnull GrpcModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation) {
		return new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
			mutation.getName(),
			toAttributeInheritanceBehavior(mutation.getAttributeInheritanceBehavior()),
			mutation.getAttributeInheritanceFilterList().toArray(String[]::new)
		);
	}

	@Nonnull
	public GrpcModifyReflectedReferenceAttributeInheritanceSchemaMutation convert(@Nonnull ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation) {
		final GrpcModifyReflectedReferenceAttributeInheritanceSchemaMutation.Builder builder = GrpcModifyReflectedReferenceAttributeInheritanceSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.setAttributeInheritanceBehavior(toGrpcAttributeInheritanceBehavior(mutation.getAttributeInheritanceBehavior()));

		for (String attributeName : mutation.getAttributeInheritanceFilter()) {
			builder.addAttributeInheritanceFilter(attributeName);
		}

		return builder.build();
	}
}
