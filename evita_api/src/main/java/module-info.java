/**
 * Module contains external API of the evitaDB.
 */
module evita.api {
	uses io.evitadb.api.trace.TracingContext;

	opens io.evitadb.api.configuration to com.fasterxml.jackson.databind;
	opens io.evitadb.api.requestResponse.extraResult to com.graphqljava;

	exports io.evitadb.api;
	exports io.evitadb.api.configuration;
	exports io.evitadb.api.proxy;
	exports io.evitadb.api.proxy.impl;
	exports io.evitadb.api.requestResponse;
	exports io.evitadb.api.requestResponse.data;
	exports io.evitadb.api.requestResponse.data.key;
	exports io.evitadb.api.requestResponse.data.structure;
	exports io.evitadb.api.requestResponse.data.mutation;
	exports io.evitadb.api.requestResponse.data.mutation.associatedData;
	exports io.evitadb.api.requestResponse.data.mutation.attribute;
	exports io.evitadb.api.requestResponse.data.mutation.entity;
	exports io.evitadb.api.requestResponse.data.mutation.price;
	exports io.evitadb.api.requestResponse.data.mutation.reference;
	exports io.evitadb.api.requestResponse.data.structure.predicate;
	exports io.evitadb.api.requestResponse.extraResult;
	exports io.evitadb.api.requestResponse.schema;
	exports io.evitadb.api.requestResponse.schema.mutation;
	exports io.evitadb.api.requestResponse.schema.builder;
	exports io.evitadb.api.requestResponse.schema.mutation.associatedData;
	exports io.evitadb.api.requestResponse.schema.mutation.attribute;
	exports io.evitadb.api.requestResponse.schema.mutation.catalog;
	exports io.evitadb.api.requestResponse.schema.mutation.entity;
	exports io.evitadb.api.requestResponse.schema.mutation.reference;
	exports io.evitadb.api.requestResponse.schema.dto;
	exports io.evitadb.api.requestResponse.system;
	exports io.evitadb.api.exception;
	exports io.evitadb.api.requestResponse.mutation;
	exports io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;
	exports io.evitadb.api.requestResponse.data.annotation;
	exports io.evitadb.api.configuration.metric;
	exports io.evitadb.api.trace;

	requires static lombok;
	requires static jsr305;
	requires org.slf4j;

	requires zero.allocation.hashing;
	requires evita.common;
	requires evita.query;
	requires static proxycian.bytebuddy;

}