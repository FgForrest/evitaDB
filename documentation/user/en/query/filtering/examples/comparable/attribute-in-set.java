final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeInSet(
						"code", 
						"garmin-fenix-6-solar", 
						"garmin-approach-s42-2", 
						"garmin-vivomove-luxe", 
						"garmin-vivomove-luxe-2"
					)
				),
				require(
					entityFetch(
						attributeContent("code")
					)
				)
			)
		);
	}
);