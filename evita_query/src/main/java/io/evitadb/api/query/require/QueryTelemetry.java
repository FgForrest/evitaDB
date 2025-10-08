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

package io.evitadb.api.query.require;

import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `queryTelemetry` requirement triggers creation of the {@link QueryTelemetry} DTO and including it the evitaDB
 * response.
 *
 * Example:
 *
 * <pre>
 * queryTelemetry()
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/debug#query-telemetry">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "queryTelemetry",
	shortDescription = "The constraint triggers computation of query telemetry (explaining what operations were performed and how long they took) in extra results of the response.",
	userDocsLink = "/documentation/query/requirements/debug#query-telemetry"
)
public class QueryTelemetry extends AbstractRequireConstraintLeaf implements GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -5121347556508500340L;

	private QueryTelemetry(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public QueryTelemetry() {
		super();
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new QueryTelemetry(newArguments);
	}
}
