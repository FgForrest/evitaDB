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
	requires evita.export.fs;
	requires evita.query;
	requires com.fasterxml.jackson.databind;
	requires evita.server;
	requires evita.external.api.core;
	requires org.junit.platform.launcher;
	requires org.junit.platform.engine;
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
	requires evita.roaringbitmap;
	requires org.reactivestreams;
	requires io.netty.common;
	requires awaitility;

	/*
		The test modules run on the classpath, where the `META-INF/services` files are what registers
		these providers. These `provides` clauses are the module-path equivalent - without them a
		module-path run would silently drop both the directory cleaner and the tag-policy gate, and
		nothing would report the loss.
	*/
	provides org.junit.platform.launcher.TestExecutionListener
		with io.evitadb.test.extension.CleaningTestExecutionListener;
	provides org.junit.platform.launcher.PostDiscoveryFilter
		with io.evitadb.test.extension.TestTagPolicyFilter;
}
