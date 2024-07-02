/**
 * Module contains gRPC Java driver (gRPC client) for evitaDB.
 */
module evita.java.driver {
	uses io.evitadb.driver.trace.ClientTracingContext;

	exports io.evitadb.driver;
	exports io.evitadb.driver.config;
	exports io.evitadb.driver.certificate;
	exports io.evitadb.driver.trace;

	requires static jsr305;
	requires static lombok;
	requires org.slf4j;
	requires protobuf.java;

	requires evita.api;
	requires evita.common;
	requires evita.query;
	requires evita.external.api.grpc.shared;
	requires io.netty.handler;
	requires io.grpc;
	requires com.google.common;
	requires io.grpc.stub;
	requires com.linecorp.armeria.grpc;
	requires com.linecorp.armeria;

}