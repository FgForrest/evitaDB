var evita = new EvitaClient(
	EvitaClientConfiguration.builder()
		.host("demo.evitadb.io")
		.port(5555)
        // demo server provides Let's encrypt trusted certificate
	.useGeneratedCertificate(false)
        // the client will not be mutually verified by the server side
        .mtlsEnabled(false)
		.build()
);
// open session manually
final EvitaSessionContract session = evita.createReadOnlySession("evita");
