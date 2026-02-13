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

package io.evitadb.dataType;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A mutable container that holds a nullable reference to an object of type `T`. This class serves
 * as a simple wrapper allowing a reference to be passed around and updated across method boundaries,
 * particularly in contexts where a final or effectively final variable is required (e.g. lambdas)
 * but the referenced value needs to change.
 *
 * @param <T> the type of the held reference
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class ReferenceKeeper<T> {
	/**
	 * The held reference, may be null.
	 */
	@Nullable private T reference;

	/**
	 * Creates a new instance with the given initial reference.
	 *
	 * @param reference the initial reference to hold, may be null
	 */
	public ReferenceKeeper(@Nullable T reference) {
		this.reference = reference;
	}

	/**
	 * Replaces the currently held reference with the given value.
	 *
	 * @param reference the new reference to hold, may be null
	 */
	public void setReference(@Nullable T reference) {
		this.reference = reference;
	}

	/**
	 * Returns the currently held reference.
	 *
	 * @return the held reference, or null if none is set
	 */
	@Nullable
	public T getReference() {
		return this.reference;
	}
}
