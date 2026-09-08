# K 扫描归一化结果（源：jmh-ksweep.csv）

| 方向 | 方法 | K | ops/s(批) | ±% | objects/s | B/op(批) | B/object |
|---|---|---:|---:|---:|---:|---:|---:|
| 读 | readKAnalytics | 1 | 3916 | 22.7% | 3916 | 129314 | 129313.8 |
| 读 | readKAnalytics | 16 | 489 | 93.0% | 7820 | 1988004 | 124250.2 |
| 读 | readKAnalytics | 128 | 61 | 86.2% | 7822 | 15864640 | 123942.5 |
| 读 | readKCustomer | 1 | 100864 | 17.5% | 100864 | 4988 | 4988.1 |
| 读 | readKCustomer | 16 | 84512 | 64.0% | 1352186 | 8608 | 538.0 |
| 读 | readKCustomer | 128 | 25583 | 46.7% | 3274655 | 36628 | 286.2 |
| 读 | readKOrder | 1 | 64196 | 54.3% | 64196 | 11224 | 11224.1 |
| 读 | readKOrder | 16 | 10340 | 22.7% | 165445 | 54473 | 3404.5 |
| 读 | readKOrder | 128 | 1563 | 22.6% | 200082 | 379409 | 2964.1 |
| 读 | readKPoint | 1 | 223857 | 15.4% | 223857 | 3472 | 3472.0 |
| 读 | readKPoint | 16 | 100080 | 27.4% | 1601286 | 5384 | 336.5 |
| 读 | readKPoint | 128 | 26092 | 88.0% | 3339740 | 18824 | 147.1 |
| 读 | readKUnicodeList | 1 | 37494 | 24.8% | 37494 | 17292 | 17292.2 |
| 读 | readKUnicodeList | 16 | 2671 | 20.4% | 42739 | 221795 | 13862.2 |
| 读 | readKUnicodeList | 128 | 319 | 11.0% | 40868 | 1751190 | 13681.2 |
| 写 | writeKAnalytics | 1 | 8652 | 18.2% | 8652 | 87521 | 87520.8 |
| 写 | writeKAnalytics | 16 | 322 | 20.9% | 5152 | 1358294 | 84893.4 |
| 写 | writeKAnalytics | 128 | 39 | 26.0% | 5023 | 10845443 | 84730.0 |
| 写 | writeKCustomer | 1 | 674333 | 14.7% | 674333 | 2896 | 2896.0 |
| 写 | writeKCustomer | 16 | 121043 | 13.2% | 1936687 | 5096 | 318.5 |
| 写 | writeKCustomer | 128 | 27212 | 102.5% | 3483154 | 23104 | 180.5 |
| 写 | writeKOrder | 1 | 189751 | 39.5% | 189751 | 4944 | 4944.0 |
| 写 | writeKOrder | 16 | 25274 | 81.1% | 404384 | 24936 | 1558.5 |
| 写 | writeKOrder | 128 | 1875 | 44.1% | 239998 | 176508 | 1379.0 |
| 写 | writeKPoint | 1 | 1343163 | 24.8% | 1343163 | 2592 | 2592.0 |
| 写 | writeKPoint | 16 | 395233 | 77.4% | 6323723 | 3736 | 233.5 |
| 写 | writeKPoint | 128 | 27911 | 21.9% | 3572613 | 13904 | 108.6 |
| 写 | writeKUnicodeList | 1 | 67642 | 23.8% | 67642 | 8008 | 8008.1 |
| 写 | writeKUnicodeList | 16 | 3538 | 19.3% | 56607 | 90994 | 5687.1 |
| 写 | writeKUnicodeList | 128 | 445 | 15.1% | 56941 | 710736 | 5552.6 |

## 决策指标：K=128 vs K=1

| 方法 | objects/s 提升倍数(K128/K1) | B/object 下降倍数(K1时/K128时) | 判读 |
|---|---:|---:|---|
| readKAnalytics | 2.00× | 1.04× | objects/s 明显提升 ⇒ 固定成本占比显著 |
| readKCustomer | 32.47× | 17.43× | objects/s 大幅提升 ⇒ per-stream 固定成本在小 K 时主导 |
| readKOrder | 3.12× | 3.79× | objects/s 大幅提升 ⇒ per-stream 固定成本在小 K 时主导 |
| readKPoint | 14.92× | 23.61× | objects/s 大幅提升 ⇒ per-stream 固定成本在小 K 时主导 |
| readKUnicodeList | 1.09× | 1.26× | objects/s 基本持平 ⇒ per-object 成本主导 |
| writeKAnalytics | 0.58× | 1.03× | objects/s 基本持平 ⇒ per-object 成本主导 |
| writeKCustomer | 5.17× | 16.04× | objects/s 大幅提升 ⇒ per-stream 固定成本在小 K 时主导 |
| writeKOrder | 1.26× | 3.59× | objects/s 基本持平 ⇒ per-object 成本主导 |
| writeKPoint | 2.66× | 23.86× | objects/s 明显提升 ⇒ 固定成本占比显著 |
| writeKUnicodeList | 0.84× | 1.44× | objects/s 基本持平 ⇒ per-object 成本主导 |
