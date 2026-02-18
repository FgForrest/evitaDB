/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.GroupHaving;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum ReferenceIndexedComponents {

	/**
	 * The reference has index for the referenced entity allowing it to be queried by {@link EntityHaving} constraint.
	 * This means that the reference can be used in query filtering and sorting by the existence of the reference and
	 * by the attributes of the referenced entity.
	 */
	REFERENCED_ENTITY,
	/**
	 * The reference has index for the referenced group entity allowing it to be queried by {@link GroupHaving} constraint.
	 * This means that the reference can be used in query filtering and sorting by the existence of the reference and
	 * by the attributes of the referenced group entity.
	 */
	REFERENCED_GROUP_ENTITY

}
