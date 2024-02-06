EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	Require(
        		AttributeHistogram(20, Standard, "width", "height")
        	)
        )
	)
);