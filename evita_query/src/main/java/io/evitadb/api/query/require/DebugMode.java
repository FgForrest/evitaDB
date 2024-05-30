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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.require;

import io.evitadb.dataType.SupportedEnum;

/**
 * Determines the extra mode for the query planning and execution to support our integration tests.
 */
@SupportedEnum
public enum DebugMode {

	/**
	 * This option triggers verification of computation results for all possible indexes. This mode allows us to verify
	 * that all alternative indexes produce the same results.
	 *
	 * BEWARE: triggering this debug mode will slow down the query response because all alternative computation paths
	 * needs to be computed along the way.
	 */
	VERIFY_ALTERNATIVE_INDEX_RESULTS,
	/**
	 * This option trigger creation and verification of all possible variants of computational tree where the results
	 * are exchanged with cached variants. This mode allows us to verify that all variants of cacheable tree parts
	 * produce the same results.
	 */
	VERIFY_POSSIBLE_CACHING_TREES

}
