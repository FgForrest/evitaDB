```json
{
  "operation": "OVERALL",
  "start": 177425764174411,
  "steps": [
    {
      "operation": "PLANNING",
      "start": 177425764186033,
      "steps": [
        {
          "operation": "PLANNING_INDEX_USAGE",
          "start": 177425764188768,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000012364s"
        },
        {
          "operation": "PLANNING_FILTER",
          "start": 177425764211030,
          "steps": [
            {
              "operation": "PLANNING_FILTER_ALTERNATIVE",
              "start": 177425764217070,
              "steps": [ ],
              "arguments": [
                "Index type: GLOBAL, estimated costs 585"
              ],
              "spentTime": "0.000986590s"
            }
          ],
          "arguments": [
            "Selected index: Index type: GLOBAL, estimated costs 585"
          ],
          "spentTime": "0.001002448s"
        },
        {
          "operation": "PLANNING_SORT",
          "start": 177425765215422,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000113883s"
        },
        {
          "operation": "PLANNING_EXTRA_RESULT_FABRICATION",
          "start": 177425765330918,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000009688s"
        }
      ],
      "arguments": [ ],
      "spentTime": "0.001157799s"
    },
    {
      "operation": "EXECUTION",
      "start": 177425765381451,
      "steps": [
        {
          "operation": "EXECUTION_FILTER",
          "start": 177425765386741,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000043041s"
        },
        {
          "operation": "EXECUTION_SORT_AND_SLICE",
          "start": 177425765430594,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000157423s"
        }
      ],
      "arguments": [ ],
      "spentTime": "0.000262340s"
    }
  ],
  "arguments": [ ],
  "spentTime": "0.001470131s"
}
```