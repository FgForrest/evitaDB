/**
 * Module contains the evitaDB key-value store implementation.
 */
module evita.store.key.value {

	exports io.evitadb.store.kryo;
	exports io.evitadb.store.fileOffsetIndex;
	exports io.evitadb.store.fileOffsetIndex.stream;
	exports io.evitadb.store.fileOffsetIndex.exception;
	exports io.evitadb.store.fileOffsetIndex.model;

	uses io.evitadb.store.service.StoragePartRegistry;

	requires static lombok;
	requires static jsr305;
	requires org.slf4j;
	requires com.esotericsoftware.kryo;

	requires evita.api;
	requires evita.common;
	requires evita.store.core;

}