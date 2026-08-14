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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.store.entity.serializer.EnumNameSerializer;

import javax.annotation.Nonnull;

/**
 * Reads and writes {@link EntityIndexType} in exactly the byte layout {@link EnumNameSerializer} produces - the class
 * registration followed by {@link Enum#name()} - and additionally accepts one name the enum no longer declares.
 *
 * **Why this exists instead of the generic serializer.** `REFERENCED_HIERARCHY_NODE` was merged into
 * {@link EntityIndexType#REFERENCED_ENTITY} in 2024.12 for holding the same data, and the constant was dropped once
 * nothing produced it. Catalogs written before that merge still carry the retired name on disk, and the generic
 * serializer would resolve it through `Enum.valueOf` and fail the catalog load with an `IllegalArgumentException`
 * - a data-shaped failure on the very catalog an operator is trying to open.
 *
 * Folding here rather than in {@link EntityIndexStoragePartSerializer_2024_11} - which is where the fold lived while
 * the constant still existed - is deliberate: every storage-part serializer version reads the type through this one
 * registration, so a part of *any* vintage carrying the retired name is handled, and no one has to prove that only the
 * 2024.11 format can carry it.
 *
 * The write side never emits the retired name: it is unreachable from {@link EntityIndexType}, so a flush rewrites a
 * legacy index under the type it was merged into, which is the same collapse the engine has performed in memory since
 * 2024.12.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see EnumNameSerializer
 */
public class EntityIndexTypeSerializer extends Serializer<EntityIndexType> {
	/**
	 * The name of the entity index type retired in 2024.12, as it still appears in catalogs written before then.
	 */
	private static final String RETIRED_REFERENCED_HIERARCHY_NODE = "REFERENCED_HIERARCHY_NODE";

	@Override
	public void write(Kryo kryo, Output output, EntityIndexType object) {
		kryo.writeClass(output, object.getClass());
		output.writeString(object.name());
	}

	@Nonnull
	@Override
	public EntityIndexType read(Kryo kryo, Input input, Class<? extends EntityIndexType> type) {
		// the class is consumed rather than used - the requested type is already known, and honouring whatever the
		// stream names would only reintroduce the failure mode this class exists to remove
		kryo.readClass(input);
		final String name = input.readString();
		return RETIRED_REFERENCED_HIERARCHY_NODE.equals(name) ?
			EntityIndexType.REFERENCED_ENTITY : EntityIndexType.valueOf(name);
	}

}
