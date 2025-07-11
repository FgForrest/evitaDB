final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				require(
					attributeHistogram(20, STANDARD, "width", "height")
				)
			)
		);
	}
);