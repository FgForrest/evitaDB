```json
{
  "data": [
    {
      "primaryKey": 108528,
      "attributes": {
        "code": "samsung-galaxy-watch-4"
      },
      "references": {
        "groups": [
          {
            "referencedKey": 112769,
            "referencedEntity": {
              "primaryKey": 112769,
              "attributes": {
                "code": "special-offer-group"
              },
              "references": {
                "tags": [
                  {
                    "group": 0,
                    "referencedKey": 11,
                    "referencedEntity": {
                      "primaryKey": 11,
                      "attributes": {
                        "code": "special-offer"
                      },
                      "references": {
                        "categories": [
                          {
                            "referencedKey": 8
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
          }
        ]
      }
    }
  ],
  "first": true,
  "last": true,
  "lastPageItemNumber": 1,
  "lastPageNumber": 1,
  "pageNumber": 1,
  "pageSize": 20,
  "singlePage": true,
  "totalRecordCount": 1
}
```