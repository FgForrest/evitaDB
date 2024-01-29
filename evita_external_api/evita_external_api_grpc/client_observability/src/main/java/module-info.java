/**
 * Module contains gRPC Java driver (gRPC client) observability extension realized via OpenTelemetry.
 */
module evita.java.driver.observability {
	uses io.evitadb.driver.trace.ClientTracingContext;
	uses io.evitadb.api.trace.TracingContext;

	provides io.evitadb.driver.trace.ClientTracingContext with io.evitadb.driver.observability.trace.DriverTracingContext;
	provides io.evitadb.api.trace.TracingContext with io.evitadb.driver.observability.trace.OpenTelemetryClientTracingContext;

	requires static jsr305;
	requires static lombok;

	requires io.grpc;

	requires evita.api;
	requires evita.java.driver;

	requires io.opentelemetry.sdk.trace;
	requires io.opentelemetry.sdk;
	requires io.opentelemetry.context;
	requires io.opentelemetry.api;
	requires io.opentelemetry.sdk.common;
	requires io.opentelemetry.semconv;
	requires io.opentelemetry.exporter.logging;
	requires io.opentelemetry.exporter.otlp;
	requires io.opentelemetry.sdk.autoconfigure;
	requires io.opentelemetry.instrumentation.grpc_1_6;

	exports io.evitadb.driver.observability.trace;
}