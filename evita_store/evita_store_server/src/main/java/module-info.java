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

import io.evitadb.store.catalog.DefaultCatalogPersistenceServiceFactory;
import io.evitadb.store.index.service.IndexStoragePartRegistry;
import io.evitadb.store.service.StoragePartRegistry;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;

/**
 * Module contains persistence logic for evitaDB server data structures.
 */
module evita.store.server {

	exports io.evitadb.store.cache;
	exports io.evitadb.store.catalog;
	exports io.evitadb.store.index.serializer;
	exports io.evitadb.store.index.service;
	exports io.evitadb.store.query;
	exports io.evitadb.store.schema;

	provides CatalogPersistenceServiceFactory with DefaultCatalogPersistenceServiceFactory;
	provides StoragePartRegistry with IndexStoragePartRegistry;

	requires static lombok;
	requires static jsr305;
	requires org.slf4j;
	requires com.esotericsoftware.kryo;
	requires roaringbitmaps.workaround.build;

	requires evita.api;
	requires evita.engine;
	requires evita.common;
	requires evita.query;
	requires evita.store.core;
	requires evita.store.entity;
	requires evita.store.memtable;

}