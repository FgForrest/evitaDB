EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeStartsWith("code", "lenovo")
        	),
        	OrderBy(
        		AttributeSetExact(
        			"code", 
        			"lenovo-tab-m8-3rd-generation", 
        			"lenovo-yoga-tab-13", 
        			"lenovo-tab-m10-fhd-plus-3rd-generation-1"
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);