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

package io.evitadb.externalApi.http;

import io.undertow.util.StatusCodes;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents successful response. Either {@link StatusCodes#OK} or {@link StatusCodes#NO_CONTENT} depending on passed body
 * object.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SuccessEndpointResponse<R> implements EndpointResponse<R> {

	@Nullable
	@Getter
	private final R body;

	public SuccessEndpointResponse() {
		this.body = null;
	}

	public SuccessEndpointResponse(@Nonnull R body) {
		this.body = body;
	}
}
