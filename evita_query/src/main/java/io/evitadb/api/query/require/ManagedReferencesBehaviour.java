/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

/**
 * This enumeration controls behavior of the {@link ReferenceContent} related to managed entities.
 * If the target entity is not (yet) present in the database and {@link ManagedReferencesBehaviour#EXISTING} is set,
 * the reference will not be returned as if it does not exist.
 * If {@link ManagedReferencesBehaviour#ANY} is set (default behavior), the reference will be returned if defined regardless
 * of its target entity existence.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public enum ManagedReferencesBehaviour {

	/**
	 * The reference to managed entity will always be returned regardless of the target entity existence.
	 */
	ANY,
	/**
	 * The reference to managed entity will be returned only if the target entity exists in the database.
	 */
	EXISTING

}
