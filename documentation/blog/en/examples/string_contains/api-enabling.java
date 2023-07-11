final ExternalApiServer externalApiServer = new ExternalApiServer(
	evita,
	ApiOptions.builder()
		.enable(GraphQLProvider.CODE)
		.build()
);
// open the API on configured ports
externalApiServer.start();
// close the server and close the ports
externalApiServer.stop();