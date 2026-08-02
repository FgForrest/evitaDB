# #760 vs dev — EvitaWarmUpInsertionTest timings (N=500k insert + 500k churn)

Workload: HEAD version of EvitaWarmUpInsertionTest run on both branches (fixed workload, engine varies).
Machine: 24 cores, 93Gi RAM. Single surefire fork, serviceThreadPool maxThreadCount=1.

## #760 (HEAD ab9b2c489) — COMPLETE, all 6 green (2578s total)
| index  | mode      | insert | churn    | set-up   |
|--------|-----------|--------|----------|----------|
| unique | WARMING_UP| 5s     | 13s      | 21s      |
| unique | ALIVE     | 4s     | 25m49s   | 25m55s   |  <-- OUTLIER
| range  | WARMING_UP| 5s     | 13s      | 21s      |
| range  | ALIVE     | 6s     | 7m41s    | 7m48s    |
| chain  | WARMING_UP| 4s     | 1m2s     | 1m8s     |
| chain  | ALIVE     | 3s     | 7m15s    | 7m20s    |

## dev (origin/dev 2b045ab97) — PENDING
