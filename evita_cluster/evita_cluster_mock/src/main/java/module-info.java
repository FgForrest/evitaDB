module evita.cluster.mock {
	exports io.evitadb.cluster.mock;
	exports io.evitadb.cluster.mock.configuration;
	opens io.evitadb.cluster.mock to com.fasterxml.jackson.databind;
	opens io.evitadb.cluster.mock.configuration to com.fasterxml.jackson.databind;

	provides io.evitadb.spi.cluster.EnvironmentServiceFactory
		with io.evitadb.cluster.mock.MockEnvironmentServiceFactory;

	requires static lombok;
	requires evita.api;
	requires evita.engine;
	requires evita.common;
	requires jsr305;
	requires org.slf4j;
}
