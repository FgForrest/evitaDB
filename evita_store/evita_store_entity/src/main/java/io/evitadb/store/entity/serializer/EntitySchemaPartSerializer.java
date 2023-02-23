/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.store.entity.model.schema.EntitySchemaStoragePart;

/**
 * This {@link Serializer} implementation reads/writes {@link EntitySchemaStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntitySchemaPartSerializer extends Serializer<EntitySchemaStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, EntitySchemaStoragePart entitySchemaStoragePart) {
		kryo.writeObject(output, entitySchemaStoragePart.entitySchema());
	}

	@Override
	public EntitySchemaStoragePart read(Kryo kryo, Input input, Class<? extends EntitySchemaStoragePart> type) {
		return new EntitySchemaStoragePart(kryo.readObject(input, EntitySchema.class));
	}

}
