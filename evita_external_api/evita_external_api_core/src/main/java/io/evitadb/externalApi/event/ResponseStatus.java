/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.externalApi.event;

/**
 * Represents the response status of an API request execution at the application level.
 * Shared across all external API event types (GraphQL, REST, gRPC) to provide
 * a unified classification of request outcomes.
 *
 * - {@code OK}: The request completed successfully.
 * - {@code ERROR}: The request encountered an error during processing.
 * - {@code TIMEOUT}: The request has timed out on the server side.
 * - {@code CANCELLED}: The request was cancelled (e.g. client disconnected).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public enum ResponseStatus {
	OK,
	ERROR,
	TIMEOUT,
	CANCELLED
}
