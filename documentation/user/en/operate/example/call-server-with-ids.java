var evita = new EvitaClient(
	EvitaClientConfiguration.builder()
		.host("demo.evitadb.io")
		.port(5555)
		// specify the client for all requests
		.clientId("my-client-id")
        // demo server provides Let's encrypt trusted certificate
		.useGeneratedCertificate(false)
        // the client will not be mutually verified by the server side
        .mtlsEnabled(false)
		.build()
);
// call the server
var entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.executeWithRequestId(
			// the id valid for all calls
			"listing brands starting with A in English",
			// in following lambda (code block)
			() -> {
				return session.queryListOfSealedEntities(
					query(
						collection("Brand"),
						filterBy(
							and(
								attributeStartsWith("name", "A"),
								entityLocaleEquals(Locale.ENGLISH)
							)
						),
						orderBy(
							attributeNatural("name", OrderDirection.ASC)
						),
						require(entityFetchAll())
					)
				);
			}
		);
	}
)
