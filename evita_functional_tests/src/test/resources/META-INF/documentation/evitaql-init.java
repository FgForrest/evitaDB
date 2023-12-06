var evita = new EvitaClient(
	"LOCALHOST".equals(documentationProfile) ?
		EvitaClientConfiguration.builder()
		.host("localhost")
		.port(5556)
		.useGeneratedCertificate(true)
		.mtlsEnabled(true)
		.build() :
	EvitaClientConfiguration.builder()
		.host("demo.evitadb.io")
		.port(5556)
        // demo server provides Let's encrypt trusted certificate
		.useGeneratedCertificate(false)
        // the client will not be mutually verified by the server side
        .mtlsEnabled(false)
		.build()
);