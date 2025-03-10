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
 * Represents a error on the server side response. The {@link HttpStatus#BAD_REQUEST} will be returned along
 * with data of passed body object. The result object must be in serializable form and thus be ready to be serialized.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2025
 */
public class ClientErrorEndpointResponse implements EndpointResponse {
	public static final ClientErrorEndpointResponse NO_RESPONSE = new ClientErrorEndpointResponse();

	@Nullable
	@Getter
	private final Object result;

	public ClientErrorEndpointResponse() {
		this.result = null;
	}

	public ClientErrorEndpointResponse(@Nullable Object result) {
		this.result = result;
	}

}
