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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.store.kryo.ObservableInput;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.function.Function;

/**
 * This implementation of {@link ReadOnlyHandle} is used for reading data from passed {@link ObservableInput}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class ReadOnlyGenericHandle implements ReadOnlyHandle {
	private final ObservableInput<?> observableInput;
	private final long lastWrittenPosition;

	@Override
	public <T> T execute(@Nonnull Function<ObservableInput<?>, T> logic) {
		return logic.apply(this.observableInput);
	}

	@Override
	public void close() {
		this.observableInput.close();
	}

	@Override
	public long getLastWrittenPosition() {
		return this.lastWrittenPosition;
	}

}
