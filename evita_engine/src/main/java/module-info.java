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

import io.evitadb.store.spi.CatalogPersistenceServiceFactory;

/**
 * The implementation of evitaDB.
 */
module evita.engine {

	exports io.evitadb.core;
	exports io.evitadb.core.cache;
	exports io.evitadb.core.cache.payload;
	exports io.evitadb.core.cdc;
	exports io.evitadb.core.buffer;
	exports io.evitadb.core.query.response;
	exports io.evitadb.core.query.algebra.price.filteredPriceRecords;
	exports io.evitadb.core.query.algebra.price.termination;
	exports io.evitadb.core.query.algebra;
	exports io.evitadb.core.query.extraResult.translator.histogram.cache;
	exports io.evitadb.core.sequence;
	exports io.evitadb.index;
	exports io.evitadb.index.bool;
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
	exports io.evitadb.index.facet;
	exports io.evitadb.index.price.model.priceRecord;
	exports io.evitadb.index.transactionalMemory;
	exports io.evitadb.index.transactionalMemory.diff;
	exports io.evitadb.store.spi;
	exports io.evitadb.store.spi.model;
	exports io.evitadb.store.spi.model.storageParts.index;
	exports io.evitadb.store.spi.exception;
	exports io.evitadb.core.query;

	uses CatalogPersistenceServiceFactory;

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
	requires jboss.threads;
	requires roaringbitmap;
	requires com.esotericsoftware.kryo;

}