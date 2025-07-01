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

package io.evitadb.utils;


import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class providing helper methods for working with exceptions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ExceptionUtils {

	/**
	 * Finds the root cause of an exception by traversing the exception chain.
	 * This method handles circular references in the exception chain by keeping track of visited exceptions.
	 *
	 * @param throwable the throwable to find the root cause for, must not be null
	 * @return the root cause of the throwable, or the throwable itself if it has no cause
	 */
	@Nonnull
	public static Throwable getRootCause(@Nonnull Throwable throwable) {
		Throwable rootCause = throwable;
		final Set<Throwable> visited = new HashSet<>();

		while (rootCause.getCause() != null && !visited.contains(rootCause)) {
			visited.add(rootCause);
			rootCause = rootCause.getCause();
		}

		return rootCause;
	}

}
