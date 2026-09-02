/**
 * Module contains external API of the evitaDB.
 */
module evita.common {

	exports io.evitadb.dataType;
	exports io.evitadb.dataType.data;
	exports io.evitadb.dataType.map;
	exports io.evitadb.dataType.set;
	exports io.evitadb.dataType.trie;
	exports io.evitadb.dataType.exception;
	exports io.evitadb.dataType.expression;
	exports io.evitadb.function;
	exports io.evitadb.comparator;
	exports io.evitadb.exception;
	exports io.evitadb.utils;
	exports io.evitadb.dataType.array;
	exports io.evitadb.dataType.iterator;
	exports io.evitadb.dataType.bPlusTree;
	exports io.evitadb.dataType.champ;
	exports io.evitadb.stream;

	requires org.slf4j;
	requires jsr305;
	requires com.fasterxml.jackson.databind;
	requires zero.allocation.hashing;
	requires static lombok;
	requires static okhttp3;
	// `VMLayout` reads the effective UseCompressedOops / UseCompressedClassPointers / ObjectAlignmentInBytes flags
	// through HotSpotDiagnosticMXBean - they are set by VM ergonomics rather than the command line, so
	// RuntimeMXBean.getInputArguments() cannot see them. Required non-optionally so `jlink` resolves both into any
	// image; the runtime fallback in `VMLayout` covers a non-HotSpot VM that lacks the bean, not a missing module.
	requires java.management;
	requires jdk.management;

}
