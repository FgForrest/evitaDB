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

package io.evitadb.api.requestResponse.schema;

/**
 * In EvitaDB we define only one-way relationship from the perspective of the entity. We stick to the ERD modelling
 * <a href="https://www.gleek.io/blog/crows-foot-notation.html">standards</a> here.
 *
 * TOBEDONE JNO #501 - verify cardinality at the end of the transaction / warm-up
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public enum Cardinality {

	/**
	 * Relation may be missing completely, but if it exists - there is never more than single relation of this type.
	 */
	ZERO_OR_ONE,
	/**
	 * There is always single relation of this type.
	 */
	EXACTLY_ONE,
	/**
	 * Relation may be missing completely, but there may be also one or more relations of this type.
	 */
	ZERO_OR_MORE,
	/**
	 * There is always at least one relation of this type, but there may be also more than one.
	 */
	ONE_OR_MORE

}
