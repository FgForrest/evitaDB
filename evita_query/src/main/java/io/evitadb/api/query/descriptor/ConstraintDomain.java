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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.descriptor.annotation.ConstraintDef;

/**
 * Domain is primarily used to be able to define which constraints are supported in particular domain.
 * To define those constraints one should use {@link ConstraintDef#supportedIn()} to specify in which domains particular
 * query is supported.
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
public enum ConstraintDomain {

	/**
	 * Domain that does not belong to any specific data.
	 */
	GENERIC,
	/**
	 * Entity domain should contain all constraints that can filter or order by entity's properties (primary key, attributes, prices, ...).
	 */
	ENTITY,
	/**
	 * Reference domain should contain all constraints that can filter or order by reference properties (reference attributes, ...).
	 * Usually linked to {@link ConstraintPropertyType#REFERENCE}.
	 */
	REFERENCE,
	/**
	 * Hierarchy domain should contain all constraints that can filter or order by hierarchy reference properties or
	 * specify some additional inner rules (direct relation, ...).
	 * Usually linked to {@link ConstraintPropertyType#HIERARCHY}.
	 */
	HIERARCHY,
	/**
	 * Facet domain should contain all constraints that can filter or order by facet reference properties.
	 * Usually linked to {@link ConstraintPropertyType#FACET}.
	 */
	FACET
}
