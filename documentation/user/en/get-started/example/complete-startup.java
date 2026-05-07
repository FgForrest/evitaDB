// pass `scheduleCatalogLoading=false` so catalog loading is deferred until the
// external API providers below have subscribed to the system CDC stream — without
// this, host events for fast-loading catalogs can be missed and their endpoints
// will not be registered (see Evita's JavaDoc for details)
final Evita evita = new Evita(
	EvitaConfiguration.builder()
		.build(),
	false
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

// open the API on configured ports — this also kicks off the initial catalog loading
// once every external API provider has subscribed to the system CDC stream
externalApiServer.start();

// close the server and the ports, then close evitaDB itself
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
	externalApiServer.close();
	evita.close();
}));
