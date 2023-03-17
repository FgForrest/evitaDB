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

import io.undertow.util.HttpString;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Additional list of HTTP headers that are not supported by {@link io.undertow.util.Headers}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AdditionalHeaders {

	public static final String ACCESS_CONTROL_ALLOW_ORIGINS_STRING = "Access-Control-Allow-Origins";
	public static final String ACCESS_CONTROL_ALLOW_METHODS_STRING = "Access-Control-Allow-Methods";
	public static final String ACCESS_CONTROL_ALLOW_HEADERS_STRING = "Access-Control-Allow-Headers";

	public static final HttpString ACCESS_CONTROL_ALLOW_ORIGINS = new HttpString(ACCESS_CONTROL_ALLOW_ORIGINS_STRING);
	public static final HttpString ACCESS_CONTROL_ALLOW_METHODS = new HttpString(ACCESS_CONTROL_ALLOW_METHODS_STRING);
	public static final HttpString ACCESS_CONTROL_ALLOW_HEADERS = new HttpString(ACCESS_CONTROL_ALLOW_HEADERS_STRING);
}
