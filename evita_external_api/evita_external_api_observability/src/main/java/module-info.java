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
 * Module contains Observability API for evitaDB.
 */
module evita.external.api.observability {

	uses io.evitadb.store.spi.CatalogPersistenceServiceFactory;
	uses io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
	uses io.evitadb.api.observability.trace.TracingContext;
	uses io.evitadb.externalApi.utils.ExternalApiTracingContext;

	provides io.evitadb.api.observability.trace.TracingContext with io.evitadb.externalApi.observability.trace.ObservabilityTracingContext;
	provides io.evitadb.externalApi.utils.ExternalApiTracingContext with io.evitadb.externalApi.observability.trace.DelegateExternalApiTracingContext;
	provides io.evitadb.externalApi.http.ExternalApiProviderRegistrar with io.evitadb.externalApi.observability.ObservabilityProviderRegistrar;
	provides io.evitadb.externalApi.api.system.ProbesProvider with io.evitadb.externalApi.observability.metric.ObservabilityProbesDetector;

	opens io.evitadb.externalApi.observability.configuration to com.fasterxml.jackson.databind;

	requires static jsr305;
	requires static lombok;
	requires org.slf4j;
	requires evita.api;
	requires evita.common;
	requires evita.external.api.core;
	requires evita.query;
	requires evita.engine;
	requires evita.store.server;
	requires com.fasterxml.jackson.annotation;
	requires com.fasterxml.jackson.databind;
	requires java.management;

	requires io.prometheus.metrics.core;
	requires io.prometheus.metrics.instrumentation.jvm;

	requires jdk.jfr;
	requires io.grpc;

	requires io.opentelemetry.sdk.trace;
	requires io.opentelemetry.sdk;
	requires io.opentelemetry.context;
	requires io.opentelemetry.api;
	requires io.opentelemetry.sdk.common;
	requires io.opentelemetry.semconv;
	requires io.opentelemetry.exporter.logging;
	requires io.opentelemetry.exporter.otlp;
	requires io.opentelemetry.sdk.autoconfigure;
	requires java.instrument;
	requires net.bytebuddy;
	requires org.bouncycastle.provider;
	requires evita.external.api.grpc;
	requires evita.external.api.graphql;
	requires evita.external.api.rest;
	requires io.prometheus.metrics.model;
	requires com.linecorp.armeria;
	requires io.netty.common;
	requires org.reactivestreams;
	requires io.prometheus.metrics.exporter.common;
	requires io.netty.transport;
	requires com.fasterxml.jackson.datatype.jsr310;
	requires com.fasterxml.jackson.datatype.jdk8;

	exports io.evitadb.externalApi.observability.configuration;
	exports io.evitadb.externalApi.observability.trace;
	exports io.evitadb.externalApi.observability.metric;
	exports io.evitadb.externalApi.observability;
}
