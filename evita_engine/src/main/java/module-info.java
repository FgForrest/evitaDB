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

/**
 * The implementation of evitaDB.
 */
module evita.engine {

	exports io.evitadb.core;
	exports io.evitadb.core.executor;
	exports io.evitadb.core.buffer;
	exports io.evitadb.core.cache;
	exports io.evitadb.core.cache.model;
	exports io.evitadb.core.cache.payload;
	exports io.evitadb.core.file;
	exports io.evitadb.core.metric.event;
	exports io.evitadb.core.metric.event.cache;
	exports io.evitadb.core.metric.event.cdc;
	exports io.evitadb.core.metric.event.query;
	exports io.evitadb.core.metric.event.session;
	exports io.evitadb.core.metric.event.storage;
	exports io.evitadb.core.metric.event.system;
	exports io.evitadb.core.metric.event.transaction;
	exports io.evitadb.core.query;
	exports io.evitadb.core.cdc;
	exports io.evitadb.core.query.response;
	exports io.evitadb.core.query.algebra.price.filteredPriceRecords;
	exports io.evitadb.core.query.algebra.price.predicate;
	exports io.evitadb.core.query.algebra.price.termination;
	exports io.evitadb.core.query.algebra;
	exports io.evitadb.core.query.extraResult.translator.histogram.cache;
	exports io.evitadb.core.sequence;
	exports io.evitadb.core.task;
	exports io.evitadb.core.traffic;
	exports io.evitadb.core.transaction;
	exports io.evitadb.core.transaction.memory;
	exports io.evitadb.core.transaction.stage.mutation;
	exports io.evitadb.index;
	exports io.evitadb.index.bool;
	exports io.evitadb.index.bPlusTree;
	exports io.evitadb.index.cardinality;
	exports io.evitadb.index.map;
	exports io.evitadb.index.list;
	exports io.evitadb.index.range;
	exports io.evitadb.index.price.model;
	exports io.evitadb.index.invertedIndex;
	exports io.evitadb.index.bitmap;
	exports io.evitadb.index.attribute;
	exports io.evitadb.index.hierarchy;
	exports io.evitadb.index.hierarchy.predicate;
	exports io.evitadb.index.price;
	exports io.evitadb.index.relation;
	exports io.evitadb.index.reference;
	exports io.evitadb.index.facet;
	exports io.evitadb.index.price.model.priceRecord;
	exports io.evitadb.store.spi;
	exports io.evitadb.store.spi.chunk;
	exports io.evitadb.store.spi.model;
	exports io.evitadb.store.spi.model.storageParts;
	exports io.evitadb.store.spi.model.reference;
	exports io.evitadb.store.spi.model.storageParts.index;
	exports io.evitadb.store.spi.exception;
	exports io.evitadb.store.spi.model.wal;

	uses io.evitadb.store.spi.EnginePersistenceServiceFactory;
	uses io.evitadb.store.spi.CatalogPersistenceServiceFactory;
	uses io.evitadb.store.spi.TrafficRecorder;

	requires static lombok;
	requires static jsr305;
	requires static org.slf4j;

	requires evita.api;
	requires evita.common;
	requires evita.query;
	requires evita.store.core;
	requires evita.store.entity;

	requires zero.allocation.hashing;
    requires com.carrotsearch.hppc;
	requires roaringbitmap;
	requires com.esotericsoftware.kryo;

	requires jdk.jfr;
	requires net.bytebuddy;
	requires proxycian.bytebuddy;

	opens io.evitadb.core.metric.event to evita.common;
	opens io.evitadb.core.metric.event.transaction to jdk.jfr;
	opens io.evitadb.core.metric.event.storage to jdk.jfr;

}
