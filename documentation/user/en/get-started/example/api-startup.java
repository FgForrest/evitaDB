// defer catalog loading until the API server has subscribed to catalog changes
final Evita evita = new Evita(
	EvitaConfiguration.builder()
		.storage(
			StorageOptions.builder()
				.storageDirectory(Path.of("./data"))
				.build()
		)
		.build(),
	false
);
final ExternalApiServer externalApiServer = new ExternalApiServer(
	evita,
	ApiOptions.builder()
		.enable(GraphQLProvider.CODE)
		.build()
);
// open the API on configured ports and start loading the catalogs
externalApiServer.start();
// close the server and close the ports
externalApiServer.close();
