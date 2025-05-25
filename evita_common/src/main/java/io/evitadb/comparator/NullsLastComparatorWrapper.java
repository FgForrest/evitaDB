/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.comparator;

import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;

/**
 * This comparator implementation wraps any other comparator and ensures that null values are always sorted last.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class NullsLastComparatorWrapper<T extends Comparable<T>> implements Comparator<T>, Serializable {
	@Serial private static final long serialVersionUID = -5630385081007433683L;
	@Nonnull private final Comparator<T> delegate;

	@Override
	public int compare(T o1, T o2) {
		if (o1 == null && o2 != null) {
			return 1;
		} else if (o2 == null && o1 != null) {
			return -1;
		} else if (o1 == null) {
			return 0;
		} else {
			return this.delegate.compare(o1, o2);
		}
	}

}
