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
	exports io.evitadb.core.buffer;
	exports io.evitadb.core.cache;
	exports io.evitadb.core.cache.model;
	exports io.evitadb.core.cache.payload;
	exports io.evitadb.core.catalog;
	exports io.evitadb.core.cdc;
	exports io.evitadb.core.collection;
	exports io.evitadb.core.engine;
	exports io.evitadb.core.executor;
	exports io.evitadb.core.management;
	exports io.evitadb.core.metric.event;
	exports io.evitadb.core.metric.event.cache;
	exports io.evitadb.core.metric.event.cdc;
	exports io.evitadb.core.metric.event.query;
	exports io.evitadb.core.metric.event.session;
	exports io.evitadb.core.metric.event.storage;
	exports io.evitadb.core.metric.event.system;
	exports io.evitadb.core.metric.event.transaction;
	exports io.evitadb.core.query;
	exports io.evitadb.core.query.response;
	exports io.evitadb.core.query.algebra.price.filteredPriceRecords;
	exports io.evitadb.core.query.algebra.price.predicate;
	exports io.evitadb.core.query.algebra.price.termination;
	exports io.evitadb.core.query.algebra;
	exports io.evitadb.core.query.extraResult.translator.histogram.cache;
	exports io.evitadb.core.query.fetch;
	exports io.evitadb.core.session;
	exports io.evitadb.core.sequence;
	exports io.evitadb.core.session.task;
	exports io.evitadb.core.traffic;
	exports io.evitadb.core.transaction;
	exports io.evitadb.core.transaction.conflict;
	exports io.evitadb.core.transaction.memory;
	exports io.evitadb.core.transaction.stage.mutation;
	exports io.evitadb.index;
	exports io.evitadb.index.bool;
	exports io.evitadb.index.bPlusTree;
	exports io.evitadb.index.cardinality;
	exports io.evitadb.index.attribute;
	exports io.evitadb.index.bitmap;
	exports io.evitadb.index.invertedIndex;
	exports io.evitadb.index.hierarchy;
	exports io.evitadb.index.hierarchy.predicate;
	exports io.evitadb.index.list;
	exports io.evitadb.index.map;
	exports io.evitadb.index.price;
	exports io.evitadb.index.price.model;
	exports io.evitadb.index.price.model.priceRecord;
	exports io.evitadb.index.range;
	exports io.evitadb.index.relation;
	exports io.evitadb.index.reference;
	exports io.evitadb.index.facet;
	exports io.evitadb.spi.store.catalog.chunk;
	exports io.evitadb.spi.store.catalog.header;
	exports io.evitadb.spi.store.catalog.header.model;
	exports io.evitadb.spi.store.catalog.exception;
	exports io.evitadb.spi.store.catalog.persistence;
	exports io.evitadb.spi.store.catalog.persistence.accessor;
	exports io.evitadb.spi.store.catalog.persistence.storageParts;
	exports io.evitadb.spi.store.catalog.persistence.storageParts.compressor;
	exports io.evitadb.spi.store.catalog.persistence.storageParts.entity;
	exports io.evitadb.spi.store.catalog.persistence.storageParts.index;
	exports io.evitadb.spi.store.catalog.persistence.storageParts.schema;
	exports io.evitadb.spi.store.catalog.shared.model;
	exports io.evitadb.spi.store.catalog.trafficRecorder;
	exports io.evitadb.spi.store.catalog.trafficRecorder.model;
	exports io.evitadb.spi.store.catalog.wal;
	exports io.evitadb.spi.store.catalog.wal.model;
	exports io.evitadb.spi.store.engine;
	exports io.evitadb.spi.store.engine.model;
	exports io.evitadb.spi.export;
	exports io.evitadb.spi.export.model;
	exports io.evitadb.spi.cluster;
	exports io.evitadb.spi.cluster.model;
	exports io.evitadb.spi.cluster.protocol.normalFlow;
	exports io.evitadb.spi.cluster.protocol.reconfiguration;
	exports io.evitadb.spi.cluster.protocol.recovery;
	exports io.evitadb.spi.cluster.protocol.stateTransfer;
	exports io.evitadb.spi.cluster.protocol.viewChange;

	uses io.evitadb.spi.export.ExportServiceFactory;
	uses io.evitadb.spi.cluster.EnvironmentServiceFactory;
	uses io.evitadb.spi.cluster.ViewStampedReplicationServiceFactory;
	uses io.evitadb.spi.store.engine.EnginePersistenceServiceFactory;
	uses io.evitadb.spi.store.catalog.persistence.CatalogPersistenceServiceFactory;
	uses io.evitadb.spi.store.catalog.trafficRecorder.TrafficRecorder;

	requires static lombok;
	requires static jsr305;
	requires static org.slf4j;

	requires evita.api;
	requires evita.common;
	requires evita.query;

	requires jdk.jfr;
	requires com.carrotsearch.hppc;
	requires com.esotericsoftware.kryo;
	requires net.bytebuddy;
	requires proxycian.bytebuddy;
	requires roaringbitmap;
	requires zero.allocation.hashing;

	opens io.evitadb.core.metric.event to evita.common;
	opens io.evitadb.core.metric.event.transaction to jdk.jfr;
	opens io.evitadb.core.metric.event.storage to jdk.jfr;

}
