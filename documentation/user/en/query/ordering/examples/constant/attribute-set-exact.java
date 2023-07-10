final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeStartsWith("code", "lenovo")
				),
				orderBy(
					attributeSetExact(
						"code", 
						"lenovo-tab-m8-3rd-generation", 
						"lenovo-yoga-tab-13", 
						"lenovo-tab-m10-fhd-plus-3rd-generation-1"
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