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

package io.evitadb.store.schema;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.store.dataType.serializer.EnumNameSerializer;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.schema.serializer.AssociatedDataSchemaSerializer;
import io.evitadb.store.schema.serializer.AttributeSchemaSerializer;
import io.evitadb.store.schema.serializer.CatalogSchemaSerializer;
import io.evitadb.store.schema.serializer.EntitySchemaSerializer;
import io.evitadb.store.schema.serializer.GlobalAttributeSchemaSerializer;
import io.evitadb.store.schema.serializer.ReferenceSchemaSerializer;
import io.evitadb.utils.Assert;

import java.util.function.Consumer;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize {@link EntitySchema}.
 */
public class SchemaKryoConfigurer implements Consumer<Kryo> {
	public static final SchemaKryoConfigurer INSTANCE = new SchemaKryoConfigurer();
	private static final int SCHEMA_BASE = 400;

	@Override
	public void accept(Kryo kryo) {
		int index = SCHEMA_BASE;
		kryo.register(CatalogSchema.class, new SerialVersionBasedSerializer<>(new CatalogSchemaSerializer(), CatalogSchema.class), index++);
		kryo.register(EntitySchema.class, new SerialVersionBasedSerializer<>(new EntitySchemaSerializer(), EntitySchema.class), index++);
		kryo.register(AttributeSchema.class, new SerialVersionBasedSerializer<>(new AttributeSchemaSerializer(), AttributeSchema.class), index++);
		kryo.register(GlobalAttributeSchema.class, new SerialVersionBasedSerializer<>(new GlobalAttributeSchemaSerializer(), GlobalAttributeSchema.class), index++);
		kryo.register(AssociatedDataSchema.class, new SerialVersionBasedSerializer<>(new AssociatedDataSchemaSerializer(), AssociatedDataSchema.class), index++);
		kryo.register(ReferenceSchema.class, new SerialVersionBasedSerializer<>(new ReferenceSchemaSerializer(), ReferenceSchema.class), index++);
		kryo.register(EvolutionMode.class, new EnumNameSerializer<>(), index++);
		kryo.register(CatalogEvolutionMode.class, new EnumNameSerializer<>(), index++);
		kryo.register(Cardinality.class, new EnumNameSerializer<>(), index++);

		Assert.isPremiseValid(index < 500, "Index count overflow.");
	}

}
