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

import io.evitadb.store.entity.service.EntityStoragePartRegistry;
import io.evitadb.store.service.StoragePartRegistry;

/**
 * Module contains logic connected with evitaDB entity model persistence. The logic is shared between server and Java
 * client.
 */
module evita.store.entity {

	exports io.evitadb.store.entity;
	exports io.evitadb.store.entity.model.entity;
	exports io.evitadb.store.entity.model.entity.price;
	exports io.evitadb.store.entity.model.schema;
	exports io.evitadb.store.entity.service;
	exports io.evitadb.store.entity.serializer;

	provides StoragePartRegistry with EntityStoragePartRegistry;

	requires static lombok;
	requires static jsr305;
	requires com.esotericsoftware.kryo;

	requires evita.api;
	requires evita.common;
	requires evita.store.core;
	requires com.carrotsearch.hppc;

}
