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

package io.evitadb.externalApi.graphql.exception;

import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.language.SourceLocation;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.List;
import java.util.Map;

/**
 * Custom Evita GraphQL error to return when exception occurs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Getter
public class EvitaGraphQLError implements GraphQLError {

	@Serial private static final long serialVersionUID = -9047809254758635878L;

	private final String message;
	private final List<SourceLocation> locations;
	private final ErrorClassification errorType;
	private final List<Object> path;
	private final Map<String, Object> extensions;

	public EvitaGraphQLError(@Nonnull String message,
	                         @Nonnull SourceLocation location,
	                         @Nonnull List<Object> path,
	                         @Nonnull Map<String, Object> extensions) {
		this.message = message;
		this.locations = List.of(location);
		this.errorType = ErrorType.DataFetchingException;
		this.path = path;
		this.extensions = extensions;
	}

	public EvitaGraphQLError(@Nonnull String message) {
		this.message = message;
		this.locations = List.of(SourceLocation.EMPTY);
		this.errorType = ErrorType.DataFetchingException;
		this.path = null;
		this.extensions = null;
	}
}
