/**
 * Module contains the evitaDB key-value store implementation.
 */
module evita.store.memtable {

	exports io.evitadb.store.kryo;
	exports io.evitadb.store.memTable;
	exports io.evitadb.store.memTable.stream;
	exports io.evitadb.store.memTable.exception;
	exports io.evitadb.store.memTable.model;

	uses io.evitadb.store.service.StoragePartRegistry;

	requires static lombok;
	requires static jsr305;
	requires org.slf4j;
	requires com.esotericsoftware.kryo;

	requires evita.api;
	requires evita.common;
	requires evita.store.core;

}