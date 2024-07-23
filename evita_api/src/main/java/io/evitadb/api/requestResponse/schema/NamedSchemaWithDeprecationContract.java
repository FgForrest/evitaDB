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

import javax.annotation.Nullable;

/**
 * Interface extends basic properties with possibility of deprecation (i.e. making the schema obsolete with a description
 * why it was made obsolete/deprecated).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface NamedSchemaWithDeprecationContract extends NamedSchemaContract {

	/**
	 * Deprecation notice contains information about planned removal of this schema from the model / client API.
	 * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
	 */
	@Nullable
	String getDeprecationNotice();

}
