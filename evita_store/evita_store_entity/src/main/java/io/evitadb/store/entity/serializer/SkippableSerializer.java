/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;

import javax.annotation.Nonnull;

/**
 * Optional payload-only skip contract for serializers. Callers own Kryo's object framing; implementations consume
 * exactly the payload their reader would consume, including any version prefix they own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface SkippableSerializer<T> {

	/**
	 * Advances past one payload without constructing its enclosing value where the layout permits it.
	 *
	 * @param kryo the decoder for nested, unframed values
	 * @param input input positioned at the payload
	 * @param type the type that would otherwise be read
	 */
	void skip(@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends T> type);

}
