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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.trace;

import javax.annotation.Nonnull;

/**
 * Reference to underlying object representing marked block of code. Allows to mark blocks of code without need to have
 * all of marked code at one place in inner function.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public interface TracingBlockReference extends AutoCloseable {

	/**
	 * Switches the block in error state. May log the error to the block if possible.
	 */
	void setError(@Nonnull Throwable error);

	/**
	 * Closes the block (ends the marked block of code)
	 */
	@Override
	void close();
}
