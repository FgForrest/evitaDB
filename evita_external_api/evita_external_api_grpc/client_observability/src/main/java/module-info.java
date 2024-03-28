/**
 * Module contains gRPC Java driver (gRPC client) observability extension realized via OpenTelemetry.
 */
module evita.java.driver.observability {
	uses io.evitadb.driver.trace.ClientTracingContext;
	uses io.evitadb.api.trace.TracingContext;

	provides io.evitadb.driver.trace.ClientTracingContext with io.evitadb.driver.observability.trace.DriverTracingContext;

	requires static jsr305;
	requires static lombok;

	requires io.grpc;

	requires evita.api;
	requires evita.java.driver;

	requires io.opentelemetry.context;
	requires io.opentelemetry.api;

	exports io.evitadb.driver.observability.trace;
}
