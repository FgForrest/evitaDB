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
 * Module contains persistence logic for evitaDB traffic engine.
 */
module evita.store.traffic {

	exports io.evitadb.store.traffic.event;

	provides io.evitadb.spi.store.catalog.trafficRecorder.TrafficRecorder with io.evitadb.store.traffic.OffHeapTrafficRecorder;

	requires static lombok;
	requires static jsr305;

	requires evita.api;
	requires evita.common;
	requires evita.engine;
	requires evita.query;
	requires evita.store.key.value;
	requires evita.store.server;

	requires org.slf4j;
	requires com.esotericsoftware.kryo;
	requires com.carrotsearch.hppc;
	requires roaringbitmap;
	requires jdk.jfr;

}
