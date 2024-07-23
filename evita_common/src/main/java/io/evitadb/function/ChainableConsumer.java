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

package io.evitadb.function;

import java.util.function.Consumer;

/**
 * Custom implementation of {@link Consumer} with {@link #andThen(ChainableConsumer)} functionality that allows to chain
 * multiple consumer calls.
 *
 * @see java.util.function.Predicate
 * @param <T> inner class type
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */

@FunctionalInterface
public interface ChainableConsumer<T> extends Consumer<T> {

	// Method to chain another Consumer
	default ChainableConsumer<T> andThen(ChainableConsumer<? super T> after) {
		return (t) ->
		{
			this.accept(t);
			after.accept(t);
		};
	}
}
