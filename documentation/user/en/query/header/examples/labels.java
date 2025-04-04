final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				head(
					collection("Product"),
					label("query-name", "my-query"),
					label("url", "/test-url")
				),
				filterBy(
					entityPrimaryKeyInSet(1, 2, 3)
				)
			)
		);
	}
);