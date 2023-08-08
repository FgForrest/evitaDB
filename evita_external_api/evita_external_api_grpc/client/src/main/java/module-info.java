/**
 * Module contains gRPC Java driver (gRPC client) for evitaDB.
 */
module evita.java.driver {

	exports io.evitadb.driver;
	exports io.evitadb.driver.config;
	exports io.evitadb.driver.certificate;

	requires static jsr305;
	requires static lombok;
	requires org.slf4j;
	requires com.google.protobuf;
	requires grpc.workaround.build;

	requires evita.api;
	requires evita.common;
	requires evita.query;
	requires evita.external.api.grpc.shared;
	requires grpc.netty;
	requires io.netty.handler;
	requires jboss.threads;
}