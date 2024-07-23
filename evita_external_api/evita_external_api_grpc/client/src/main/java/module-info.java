/**
 * Module contains gRPC Java driver (gRPC client) for evitaDB.
 */
module evita.java.driver {
	uses io.evitadb.driver.trace.ClientTracingContext;

	exports io.evitadb.driver;
	exports io.evitadb.driver.config;
	exports io.evitadb.driver.trace;

	requires static jsr305;
	requires static lombok;

	requires org.slf4j;
	requires com.google.common;
	requires com.linecorp.armeria;
	requires com.linecorp.armeria.grpc;
	requires io.grpc;
	requires io.grpc.stub;
	requires io.netty.handler;
	requires protobuf.java;
	requires reactive.grpc.common;
	requires org.reactivestreams;

	requires evita.api;
	requires evita.common;
	requires evita.query;
	requires evita.external.api.grpc.shared;

}
