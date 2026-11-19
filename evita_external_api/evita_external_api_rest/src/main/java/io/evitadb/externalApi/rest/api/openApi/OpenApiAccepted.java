/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
 * Marks the success response of an endpoint as `202 Accepted` instead of `200 OK`: the endpoint only accepts the
 * request for processing in the background and returns the wrapped type, typically a handle to observe the
 * processing with. Like {@link OpenApiNonNull}, it is a marker translated to the wrapped type's schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
@ToString
public class OpenApiAccepted implements OpenApiWrappingType {

	@Nonnull
	@Getter
	private final OpenApiSimpleType wrappedType;

	/**
	 * Marks the wrapped type as the body of a `202 Accepted` response.
	 *
	 * @param wrappedType type of the response body
	 * @return the marker
	 */
	@Nonnull
	public static OpenApiAccepted accepted(@Nonnull OpenApiSimpleType wrappedType) {
		return new OpenApiAccepted(wrappedType);
	}

	@Nonnull
	@Override
	public Schema<?> toSchema() {
		return this.wrappedType.toSchema();
	}
}
