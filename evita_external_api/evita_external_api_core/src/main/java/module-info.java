/**
 * Module contains shared classes for evitaDB external APIs.
 */
module evita.external.api.core {

	uses io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
	uses io.evitadb.externalApi.utils.ExternalApiTracingContext;
	uses io.evitadb.externalApi.api.system.ProbesProvider;

	opens io.evitadb.externalApi.configuration;
	opens io.evitadb.externalApi.api.catalog.dataApi.dto to com.fasterxml.jackson.databind;

	exports io.evitadb.externalApi.api;
	exports io.evitadb.externalApi.configuration;
	exports io.evitadb.externalApi.exception;
	exports io.evitadb.externalApi.http;
	exports io.evitadb.externalApi.utils;
	exports io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;
	exports io.evitadb.externalApi.api.catalog.dataApi.constraint;
	exports io.evitadb.externalApi.api.catalog.dataApi.dto;
	exports io.evitadb.externalApi.api.catalog.dataApi.model;
	exports io.evitadb.externalApi.api.model;
	exports io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint;
	exports io.evitadb.externalApi.api.catalog.resolver.mutation;
	exports io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation;
	exports io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.associatedData;
	exports io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute;
	exports io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.entity;
	exports io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.price;
	exports io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference;
	exports io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;
	exports io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData;
	exports io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;
	exports io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.entity;
	exports io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog;
	exports io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;
	exports io.evitadb.externalApi.api.catalog.dataApi.model.mutation;
	exports io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;
	exports io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute;
	exports io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData;
	exports io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference;
	exports io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price;
	exports io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity;
	exports io.evitadb.externalApi.api.catalog.schemaApi.model.mutation;
	exports io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute;
	exports io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData;
	exports io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference;
	exports io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity;
	exports io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog;
	exports io.evitadb.externalApi.dataType;
	exports io.evitadb.externalApi.api.catalog.schemaApi.model;
	exports io.evitadb.externalApi.api.catalog.model;
	exports io.evitadb.externalApi.api.catalog.model.cdc;
	exports io.evitadb.externalApi.api.system.model;
	exports io.evitadb.externalApi.api.system;
	exports io.evitadb.externalApi.api.system.model.cdc;
	exports io.evitadb.externalApi.certificate;
	exports io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound;
	exports io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound;
	exports io.evitadb.externalApi.trace;
	exports io.evitadb.externalApi.utils.path;
	exports io.evitadb.externalApi.utils.path.routing;
	exports io.evitadb.externalApi.utils.path.routing.cache;
	exports io.evitadb.externalApi.serialization;
	exports io.evitadb.externalApi.event;

	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.annotation;
	requires static lombok;
	requires static jsr305;
	requires org.slf4j;
	requires com.fasterxml.jackson.databind;
	requires com.esotericsoftware.kryo;
	requires zero.allocation.hashing;

	requires evita.api;
	requires evita.common;
	requires evita.engine;
	requires evita.query;
	requires org.bouncycastle.provider;
	requires org.bouncycastle.pkix;
	requires com.linecorp.armeria;
	requires io.netty.codec.http2;
	requires io.netty.transport;
	requires io.netty.handler;
	requires io.netty.common;
	requires jdk.jfr;
}
