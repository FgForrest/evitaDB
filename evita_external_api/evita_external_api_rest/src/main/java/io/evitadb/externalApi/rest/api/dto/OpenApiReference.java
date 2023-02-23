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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.dto;

import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.Schema;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createReferenceSchema;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class OpenApiReference implements OpenApiType {

	@Nonnull
	private final String objectName;

	@Nonnull
	public static OpenApiReference from(@Nonnull String objectName) {
		return new OpenApiReference(objectName);
	}

	@Nonnull
	public static OpenApiReference from(@Nonnull OpenApiType type) {
		Assert.isPremiseValid(
			type instanceof OpenApiObject,
			() -> new OpenApiSchemaBuildingError("Cannot create reference to non-object OpenApi type.")
		);
//		return new OpenApiReference(((OpenApiObject) type).getName());
		return null;
	}

	@Nonnull
	@Override
	public Schema<Object> toSchema() {
		return createReferenceSchema(objectName);
	}
}
