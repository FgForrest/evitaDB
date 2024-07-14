var evita = await EvitaClient.Create(
	EvitaClientConfiguration.Builder()
		.Host("demo.evitadb.io")
		.Port(5555)
        // demo server provides Let's encrypt trusted certificate
		.UseGeneratedCertificate(false)
        // the client will not be mutually verified by the server side
        .MtlsEnabled(false)
		.Build()
);
