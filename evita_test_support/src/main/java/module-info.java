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
	requires rest.assured;

	requires evita.api;
	requires evita.engine;
	requires evita.common;
	requires evita.query;
	requires com.fasterxml.jackson.databind;
	requires evita.server;
	requires evita.external.api.core;
	requires org.junit.platform.launcher;
	requires org.hamcrest;
	requires evita.java.driver;
	requires evita.external.api.grpc;
	requires evita.external.api.system;
	requires evita.external.api.graphql;
	requires evita.external.api.rest;
	requires evita.external.api.lab;
	requires okhttp3;
	requires evita.external.api.observability;
	requires org.bouncycastle.pkix;
	requires org.bouncycastle.provider;
	requires com.linecorp.armeria;
	requires roaringbitmap;
	requires org.reactivestreams;
	requires io.netty.common;
	requires awaitility;
	requires kotlin.stdlib;
}
