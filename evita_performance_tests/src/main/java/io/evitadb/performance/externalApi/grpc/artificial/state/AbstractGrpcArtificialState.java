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

package io.evitadb.performance.externalApi.grpc.artificial.state;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import io.evitadb.externalApi.grpc.generated.GrpcQueryRequest;
import io.evitadb.externalApi.grpc.query.QueryConverter;
import lombok.Getter;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import javax.annotation.Nonnull;

/**
 * Common ancestor for thread-scoped state objects.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@State(Scope.Thread)
public abstract class AbstractGrpcArtificialState {

	/**
	 * Query prepared for the measured invocation.
	 */
	@Getter private GrpcQueryRequest request;

	protected void setQuery(@Nonnull Query query) {
		final StringWithParameters stringWithParameters = query.toStringWithParameterExtraction();
		this.request = GrpcQueryRequest.newBuilder()
			.setQuery(stringWithParameters.query())
			.addAllPositionalQueryParams(stringWithParameters.parameters()
				.stream()
				.map(QueryConverter::convertQueryParam)
				.toList())
			.build();
	}
}
