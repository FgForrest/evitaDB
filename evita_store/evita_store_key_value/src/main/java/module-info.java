/**
 * Module contains the evitaDB key-value store implementation.
 */
module evita.store.key.value {

	exports io.evitadb.store.kryo;
	exports io.evitadb.store.offsetIndex;
	exports io.evitadb.store.offsetIndex.stream;
	exports io.evitadb.store.offsetIndex.exception;
	exports io.evitadb.store.offsetIndex.model;
	exports io.evitadb.store.offsetIndex.io;

	uses io.evitadb.store.service.StoragePartRegistry;

	requires static lombok;
	requires static jsr305;
	requires org.slf4j;
	requires com.esotericsoftware.kryo;

	requires evita.api;
	requires evita.common;
	requires evita.store.core;
	requires evita.engine;

}