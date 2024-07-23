/**
 * Module contains external API of the evitaDB.
 */
module evita.common {

	exports io.evitadb.cdc;
	exports io.evitadb.dataType;
	exports io.evitadb.dataType.data;
	exports io.evitadb.dataType.trie;
	exports io.evitadb.dataType.exception;
	exports io.evitadb.function;
	exports io.evitadb.comparator;
	exports io.evitadb.exception;
	exports io.evitadb.utils;
	exports io.evitadb.dataType.array;
	exports io.evitadb.dataType.iterator;

	requires org.slf4j;
	requires jsr305;
	requires static lombok;
	requires com.fasterxml.jackson.databind;
	requires zero.allocation.hashing;
	requires okhttp3;

}
