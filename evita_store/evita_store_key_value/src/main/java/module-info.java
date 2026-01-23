/**
 * Module contains the evitaDB key-value store implementation.
 */
module evita.store.key.value {

	exports io.evitadb.store.kryo;
	exports io.evitadb.store.offsetIndex;
	exports io.evitadb.store.offsetIndex.exception;
	exports io.evitadb.store.offsetIndex.model;
	exports io.evitadb.store.offsetIndex.io;
	exports io.evitadb.store.shared.service;
	exports io.evitadb.store.shared.model;

	requires static lombok;
	requires static jsr305;

	requires evita.api;
	requires evita.common;
	requires evita.engine;
	requires com.esotericsoftware.kryo;
	requires com.fasterxml.jackson.databind;
	requires org.slf4j;

}