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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.TypeDefiningConstraint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Generic type of query which specifies what is purpose of the query. Each type has to correspond to some existing
 * query interface implementing {@link TypeDefiningConstraint} and other specific constraints implement that interface.
 * <p>
 * This enum exists only to make easier searching through registered constraints so that user knows which types are supported.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
@Getter
public enum ConstraintType {

	/**
	 * Marks query as header query. That means, that query will be used to specify some metadata.
	 * Conforms to {@link io.evitadb.api.query.HeadConstraint}.
	 */
	HEAD(HeadConstraint.class),
	/**
	 * Marks query as filtering one. That means, that query will be used to narrow list of results.
	 * Conforms to {@link io.evitadb.api.query.FilterConstraint}.
	 */
	FILTER(FilterConstraint.class),
	/**
	 * Marks query as ordering one. That means, that query will be for determining order of listing results.
	 * Conforms to {@link io.evitadb.api.query.OrderConstraint}.
	 */
	ORDER(OrderConstraint.class),
	/**
	 * Requirement represents an additional data passed to the query, that can somewhat alter returned result
	 *  - not in a way of filter or ordering, but rather a form.
	 * Conforms to {@link io.evitadb.api.query.RequireConstraint}.
	 */
	REQUIRE(RequireConstraint.class);

	private final Class<? extends TypeDefiningConstraint<?>> representingInterface;
}
