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

package io.evitadb.externalApi.rest.api.openApi;

import io.swagger.v3.oas.models.media.Schema;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import javax.annotation.Nonnull;

/**
 * References globally registered {@link OpenApiComplexType} (object, enum, ...) so that it can be safely used e.g. in
 * {@link OpenApiProperty}.
 *
 * It is translated to {@link Schema#$ref(String)} under the hood.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode
@ToString
public class OpenApiTypeReference implements OpenApiSimpleType {

	@Nonnull
	private final String objectName;

	/**
	 * Creates reference to registered type by its global name. The name must correspond to some registered global {@link OpenApiComplexType}
	 * (object, enum, ...).
	 * The referenced type doesn't have to be registered before creating reference to it, but must be resolved before
	 * finalizing OpenAPI specs.
	 */
	@Nonnull
	public static OpenApiTypeReference typeRefTo(@Nonnull String typeName) {
		return new OpenApiTypeReference(typeName);
	}

	/**
	 * Creates reference to passed complex type from its name.
	 * The referenced type doesn't have to be registered before creating reference to it, but must be resolved before
	 * finalizing OpenAPI specs.
	 */
	@Nonnull
	public static OpenApiTypeReference typeRefTo(@Nonnull OpenApiComplexType type) {
		return new OpenApiTypeReference(type.getName());
	}

	@Nonnull
	@Override
	public Schema<?> toSchema() {
		final Schema<Object> schema = new Schema<>();
		schema.$ref(this.objectName);
		return schema;
	}
}
