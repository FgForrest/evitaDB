EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityPrimaryKeyInSet(110066, 106742),
        		AttributeEquals("code", "lenovo-thinkpad-t495-2")
        	)
        )
	)
);