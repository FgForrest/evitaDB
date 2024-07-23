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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;

/**
 * This {@link Serializer} implementation reads/writes {@link CatalogSchemaStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CatalogSchemaStoragePartSerializer extends Serializer<CatalogSchemaStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, CatalogSchemaStoragePart entitySchemaContainer) {
		kryo.writeObject(output, entitySchemaContainer.catalogSchema());
	}

	@Override
	public CatalogSchemaStoragePart read(Kryo kryo, Input input, Class<? extends CatalogSchemaStoragePart> type) {
		return new CatalogSchemaStoragePart(kryo.readObject(input, CatalogSchema.class));
	}

}
