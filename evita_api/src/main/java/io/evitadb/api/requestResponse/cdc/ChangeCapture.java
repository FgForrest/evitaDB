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

package io.evitadb.api.requestResponse.cdc;

/**
 * CDC event that is sent to the subscriber if it matches to the request he made.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public sealed interface ChangeCapture permits ChangeSystemCapture, ChangeCatalogCapture {

	/**
	 * Returns the index of the event in the ordered CDC log.
	 */
	// todo jno: feel free to reimplement this... this is the way how we could track if the subscriber received all events
	long index();
}
