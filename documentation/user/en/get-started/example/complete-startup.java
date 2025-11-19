final Evita evita = new Evita(
	EvitaConfiguration.builder()
		.build()
);
final ExternalApiServer externalApiServer = new ExternalApiServer(
	evita,
	ApiOptions.builder()
		.enable(GrpcProvider.CODE)
		.enable(GraphQLProvider.CODE)
		.enable(RestProvider.CODE)
		.enable(SystemProvider.CODE)
		.build()
);

// open the API on configured ports
externalApiServer.start();

// close the server and the ports, then close evitaDB itself
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
	externalApiServer.close();
	evita.close();
}));
