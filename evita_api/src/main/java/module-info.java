/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
 * Module contains external API of the evitaDB.
 */
module evita.api {
	uses io.evitadb.api.observability.trace.TracingContext;

	opens io.evitadb.api.configuration to com.fasterxml.jackson.databind;
	opens io.evitadb.api.requestResponse.extraResult to com.graphqljava;

	exports io.evitadb.api;
	exports io.evitadb.api.configuration;
	exports io.evitadb.api.configuration.metric;
	exports io.evitadb.api.exception;
	exports io.evitadb.api.file;
	exports io.evitadb.api.task;
	exports io.evitadb.api.proxy;
	exports io.evitadb.api.proxy.impl;
	exports io.evitadb.api.requestResponse;
	exports io.evitadb.api.requestResponse.cdc;
	exports io.evitadb.api.requestResponse.data;
	exports io.evitadb.api.requestResponse.data.key;
	exports io.evitadb.api.requestResponse.data.structure;
	exports io.evitadb.api.requestResponse.data.mutation;
	exports io.evitadb.api.requestResponse.data.mutation.associatedData;
	exports io.evitadb.api.requestResponse.data.mutation.attribute;
	exports io.evitadb.api.requestResponse.data.mutation.parent;
	exports io.evitadb.api.requestResponse.data.mutation.price;
	exports io.evitadb.api.requestResponse.data.mutation.reference;
	exports io.evitadb.api.requestResponse.data.mutation.scope;
	exports io.evitadb.api.requestResponse.data.structure.predicate;
	exports io.evitadb.api.requestResponse.extraResult;
	exports io.evitadb.api.requestResponse.chunk;
	exports io.evitadb.api.requestResponse.progress;
	exports io.evitadb.api.requestResponse.schema;
	exports io.evitadb.api.requestResponse.schema.annotation;
	exports io.evitadb.api.requestResponse.schema.mutation;
	exports io.evitadb.api.requestResponse.schema.builder;
	exports io.evitadb.api.requestResponse.schema.mutation.associatedData;
	exports io.evitadb.api.requestResponse.schema.mutation.attribute;
	exports io.evitadb.api.requestResponse.schema.mutation.catalog;
	exports io.evitadb.api.requestResponse.schema.mutation.entity;
	exports io.evitadb.api.requestResponse.schema.mutation.reference;
	exports io.evitadb.api.requestResponse.schema.dto;
	exports io.evitadb.api.requestResponse.system;
	exports io.evitadb.api.requestResponse.mutation;
	exports io.evitadb.api.requestResponse.mutation.conflict;
	exports io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;
	exports io.evitadb.api.requestResponse.data.annotation;
	exports io.evitadb.api.requestResponse.trafficRecording;
	exports io.evitadb.api.requestResponse.transaction;
	exports io.evitadb.api.observability;
	exports io.evitadb.api.observability.trace;
	exports io.evitadb.api.observability.annotation;
	exports io.evitadb.api.requestResponse.schema.mutation.engine;

	requires static lombok;
	requires static jsr305;
	requires org.slf4j;

	requires zero.allocation.hashing;
	requires evita.common;
	requires evita.query;
	requires static proxycian.bytebuddy;
	requires evita.api;

}
