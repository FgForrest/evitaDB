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

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents array of any {@link OpenApiSimpleType}. Array is basically just wrapper, and thus it also can be used safely
 * as simple type.
 *
 * It is translated to {@link ArraySchema} under the hood.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
@ToString
public class OpenApiArray implements OpenApiWrappingType {

	@Nonnull
	private final OpenApiSimpleType itemType;
	@Nullable
	private final Integer minItems;
	@Nullable
	private final Integer maxItems;

	@Nonnull
	public static OpenApiArray arrayOf(@Nonnull OpenApiSimpleType itemType) {
		return new OpenApiArray(itemType, null, null);
	}

	@Nonnull
	public static OpenApiArray arrayOf(@Nonnull OpenApiSimpleType itemType, int minItems, int maxItems) {
		return new OpenApiArray(itemType, minItems, maxItems);
	}

	@Nonnull
	@Override
	public OpenApiSimpleType getWrappedType() {
		return this.itemType;
	}

	@Nonnull
	@Override
	public Schema<?> toSchema() {
		final Schema<Object> schema = new ArraySchema();
		schema.setItems(this.itemType.toSchema());
		schema.minItems(this.minItems);
		schema.maxItems(this.maxItems);
		return schema;
	}
}
