/**
 * Module contains shared classes for evitaDB integration testing.
 */
module evita.test.support {
	exports io.evitadb.test;
	exports io.evitadb.test.generator;
	exports io.evitadb.test.builder;

	requires static lombok;
	requires static jsr305;
	requires org.apache.commons.io;
	requires pmptt.core;
	requires org.junit.jupiter.api;
	requires org.junit.jupiter.params;
	requires javafaker;
	requires org.slf4j;
	requires ch.qos.logback.core;

	requires evita.api;
	requires evita.engine;
	requires evita.common;
	requires com.fasterxml.jackson.databind;
	requires evita.server;
	requires evita.external.api.core;
}