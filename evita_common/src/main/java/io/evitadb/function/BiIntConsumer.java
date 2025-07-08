/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import java.util.function.Consumer;

/**
 * Represents an operation that accepts a single {@code int}-valued argument and
 * returns no result.  This is the primitive type specialization of
 * {@link Consumer} for two {@code int} arguments.  Unlike most other functional interfaces,
 * {@code IntConsumer} is expected to operate via side-effects.
 *
 * <p>This is a <a href="package-summary.html">functional interface</a>
 * whose functional method is {@link #accept(int, int)}.
 *
 * @see Consumer
 * @apiNote inspired by the JDK interface
 */
@FunctionalInterface
public interface BiIntConsumer {

	/**
	 * Performs this operation on the given arguments.
	 *
	 * @param first the first input argument
	 * @param second the second input argument
	 */
	void accept(int first, int second);

}
