final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				require(
					page(
						2,
						5,
						spacing(
							gap(
								1,
								ExpressionFactory.parse("'$pageNumber % 2 == 0 && $pageNumber <= 10'")
							),
							gap(
								1,
								ExpressionFactory.parse("'$pageNumber == 1 || $pageNumber == 4'")
							)
						)
					)
				)
			)
		);
	}
);
