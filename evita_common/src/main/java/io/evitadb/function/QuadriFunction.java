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

package io.evitadb.function;

/**
 * This interface mimics Java {@link java.util.function.BiFunction} but has four input arguments.
 *
 * @apiNote inspired by the JDK interface
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@FunctionalInterface
public interface QuadriFunction<R, S, T, U, Q> {

	/**
	 * Applies this function to the given arguments.
	 *
	 * @param r the first function argument
	 * @param s the second function argument
	 * @param t the third function argument
	 * @param u the fourth function argument
	 * @return the function result
	 */
	Q apply(R r, S s, T t, U u);

}
