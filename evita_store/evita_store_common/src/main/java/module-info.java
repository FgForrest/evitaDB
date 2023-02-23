/**
 * Module contains shared logic connected with evitaDB storage layer. The logic is shared between server and Java client.
 */
module evita.store.core {

	exports io.evitadb.store.compressor;
	exports io.evitadb.store.dataType.serializer;
	exports io.evitadb.store.dataType.exception;
	exports io.evitadb.store.exception;
	exports io.evitadb.store.service;
	exports io.evitadb.store.model;

	requires static jsr305;
	requires static lombok;
	requires com.esotericsoftware.kryo;

	requires evita.common;
	requires evita.api;

}
