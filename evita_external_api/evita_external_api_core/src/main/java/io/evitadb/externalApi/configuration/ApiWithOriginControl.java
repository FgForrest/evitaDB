/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.configuration;

import javax.annotation.Nullable;

/**
 * This interface must be implemented by all API providers that support reading request HTTP origin and wishes
 * to control which are allowed and which are not. Mainly used for <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">CORS</a> handling.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ApiWithOriginControl {

	/**
	 * Returns array of allowed origins. If null, all origins are allowed.
	 */
	@Nullable
	String[] getAllowedOrigins();

}
