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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.common.HttpStatus;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * Represents a successful response. Either {@link HttpStatus#OK} or {@link HttpStatus#NO_CONTENT} depending on passed body
 * object. The result object must be in serializable form and thus be ready to be serialized.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SuccessEndpointResponse implements EndpointResponse {
	public static final SuccessEndpointResponse NO_CONTENT = new SuccessEndpointResponse();

	@Nullable
	@Getter
	private final Object result;

	private SuccessEndpointResponse() {
		this.result = null;
	}

	public SuccessEndpointResponse(@Nullable Object result) {
		this.result = result;
	}
}
