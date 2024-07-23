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

package io.evitadb.documentation.rest;

import io.evitadb.documentation.Environment;
import io.evitadb.documentation.TestContext;
import io.evitadb.test.client.RestClient;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Context creates new {@link RestClient} instance that is connected to the demo server.
 * The {@link RestClient} instance is reused between tests to speed them up.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class RestTestContext implements TestContext {
	/**
	 * Initialized client instance.
	 */
	@Getter
	private final RestClient restClient;

	public RestTestContext(@Nonnull Environment profile) {
		this.restClient = profile == Environment.LOCALHOST ?
			new RestClient("https://localhost:5555", false, false) :
			new RestClient("https://demo.evitadb.io:5555", true, false);
	}
}
