// create evita service uring Armeria gRPC client
EvitaServiceGrpc.EvitaServiceBlockingStub evitaService = GrpcClients.builder("https://demo.evitadb.io:5555/")
	.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);

// create a read-only session against our demo server
final GrpcEvitaSessionResponse sessionResponse = evitaService.createReadOnlySession(
	GrpcEvitaSessionRequest.newBuilder()
		.setCatalogName("evita")
		.build()
);

// create a session service using the acquired session ID
EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub sessionService = GrpcClients.builder("https://demo.evitadb.io:5555/")
	.intercept(new ClientSessionInterceptor())
	.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
