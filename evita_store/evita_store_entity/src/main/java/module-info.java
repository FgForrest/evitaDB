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

/**
 * Module contains logic connected with evitaDB entity model persistence. The logic is shared between server and Java
 * client.
 */
module evita.store.entity {

	exports io.evitadb.store.entity;
	exports io.evitadb.store.entity.service;
	exports io.evitadb.store.entity.serializer;

	provides io.evitadb.store.shared.service.StoragePartRegistry with io.evitadb.store.entity.service.EntityStoragePartRegistry;

	requires static lombok;
	requires static jsr305;

	requires evita.api;
	requires evita.common;
	requires evita.engine;
	requires evita.store.key.value;
	requires com.carrotsearch.hppc;
	requires com.esotericsoftware.kryo;
	requires org.apache.commons.lang3;

}
