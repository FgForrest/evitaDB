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

import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.externalApi.api.system.ProbesProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.observability.ObservabilityProviderRegistrar;
import io.evitadb.externalApi.observability.metric.ObservabilityProbesDetector;
import io.evitadb.externalApi.observability.trace.DelegateExternalApiTracingContext;
import io.evitadb.externalApi.observability.trace.ObservabilityTracingContext;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;
/**
 * Module contains Observability API for evitaDB.
 */
module evita.external.api.observability {

	uses CatalogPersistenceServiceFactory;
	uses ExternalApiProviderRegistrar;
	uses TracingContext;
	uses ExternalApiTracingContext;

	provides TracingContext with ObservabilityTracingContext;
	provides ExternalApiTracingContext with DelegateExternalApiTracingContext;
	provides ExternalApiProviderRegistrar with ObservabilityProviderRegistrar;
	provides ProbesProvider with ObservabilityProbesDetector;

	opens io.evitadb.externalApi.observability.configuration to com.fasterxml.jackson.databind;

	requires static jsr305;
	requires static lombok;
	requires org.slf4j;
	requires evita.api;
	requires evita.common;
	requires evita.external.api.core;
	requires evita.query;
	requires evita.engine;
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
	requires java.sql;
	requires evita.external.api.grpc;
	requires evita.external.api.graphql;
	requires evita.external.api.rest;
	requires io.prometheus.metrics.model;
	requires com.linecorp.armeria;
	requires io.netty.common;
	requires org.reactivestreams;
	requires io.prometheus.metrics.exporter.common;
	requires io.netty.transport;

	exports io.evitadb.externalApi.observability.configuration;
	exports io.evitadb.externalApi.observability.trace;
	exports io.evitadb.externalApi.observability.metric;
}
