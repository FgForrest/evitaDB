/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.grpc.utils;


import io.evitadb.api.query.Query;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

/**
 * Record contains parsed information from gRPC query.
 *
 * @param parsedQuery                - parsed query
 * @param positionalParameters - list of positional parameters
 * @param namedParameters      - map of named parameters
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record QueryWithParameters(
	@Nonnull Query parsedQuery,
	@Nonnull List<Object> positionalParameters,
	@Nonnull Map<String, Object> namedParameters
) {
}
