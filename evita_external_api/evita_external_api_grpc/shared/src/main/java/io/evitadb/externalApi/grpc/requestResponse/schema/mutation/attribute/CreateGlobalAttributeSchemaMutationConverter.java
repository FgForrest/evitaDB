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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute;

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCreateGlobalAttributeSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Converts between {@link CreateGlobalAttributeSchemaMutation} and {@link GrpcCreateGlobalAttributeSchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CreateGlobalAttributeSchemaMutationConverter implements SchemaMutationConverter<CreateGlobalAttributeSchemaMutation, GrpcCreateGlobalAttributeSchemaMutation> {

	@Nonnull
	public CreateGlobalAttributeSchemaMutation convert(@Nonnull GrpcCreateGlobalAttributeSchemaMutation mutation) {
		return new CreateGlobalAttributeSchemaMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null,
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null,
			EvitaEnumConverter.toAttributeUniquenessType(mutation.getUnique()),
			EvitaEnumConverter.toGlobalAttributeUniquenessType(mutation.getUniqueGlobally()),
			mutation.getFilterable(),
			mutation.getSortable(),
			mutation.getLocalized(),
			mutation.getNullable(),
			mutation.getRepresentative(),
			EvitaDataTypesConverter.toEvitaDataType(mutation.getType()),
			mutation.hasDefaultValue() ? EvitaDataTypesConverter.toEvitaValue(mutation.getDefaultValue()) : null,
			mutation.getIndexedDecimalPlaces()
		);
	}

	@Nonnull
	public GrpcCreateGlobalAttributeSchemaMutation convert(@Nonnull CreateGlobalAttributeSchemaMutation mutation) {
		final GrpcCreateGlobalAttributeSchemaMutation.Builder builder = GrpcCreateGlobalAttributeSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.setUnique(EvitaEnumConverter.toGrpcAttributeUniquenessType(mutation.getUnique()))
			.setUniqueGlobally(EvitaEnumConverter.toGrpcGlobalAttributeUniquenessType(mutation.getUniqueGlobally()))
			.setFilterable(mutation.isFilterable())
			.setSortable(mutation.isSortable())
			.setLocalized(mutation.isLocalized())
			.setNullable(mutation.isNullable())
			.setType(EvitaDataTypesConverter.toGrpcEvitaDataType(mutation.getType()))
			.setIndexedDecimalPlaces(mutation.getIndexedDecimalPlaces());

		if (mutation.getDescription() != null) {
			builder.setDescription(StringValue.of(mutation.getDescription()));
		}
		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}
		if (mutation.getDefaultValue() != null) {
			builder.setDefaultValue(EvitaDataTypesConverter.toGrpcEvitaValue(mutation.getDefaultValue()));
		}

		return builder.build();
	}
}
