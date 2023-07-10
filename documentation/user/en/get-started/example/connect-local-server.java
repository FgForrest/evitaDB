var evitaClient = new EvitaClient(
	EvitaClientConfiguration.builder()
		.host("localhost")
		.port(5556)
		.build()
);