EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeStartsWith("code", "garmin")
        	),
        	OrderBy(
        		AttributeNatural("code", Asc)
        	),
        	Require(
        		QueryTelemetry()
        	)
        )
	)
);