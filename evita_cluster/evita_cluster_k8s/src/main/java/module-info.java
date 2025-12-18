module evita.cluster.k8s {
	exports io.evitadb.cluster.k8s;
	exports io.evitadb.cluster.k8s.configuration;
	opens io.evitadb.cluster.k8s to com.fasterxml.jackson.databind;
	opens io.evitadb.cluster.k8s.configuration to com.fasterxml.jackson.databind;

	provides io.evitadb.spi.cluster.EnvironmentServiceFactory
		with io.evitadb.cluster.k8s.K8SEnvironmentServiceFactory;

	requires static lombok;
	requires evita.api;
	requires evita.engine;
	requires evita.common;
	requires jsr305;
	requires org.slf4j;
}
