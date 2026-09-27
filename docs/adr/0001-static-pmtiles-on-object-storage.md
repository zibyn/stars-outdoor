# 离线地图用对象存储上的静态 PMTiles，不运行瓦片服务器

离线地图是核心功能，而商用托管底图（MapTiler、Stadia、Thunderforest）要么禁止离线缓存，要么限额过低；天地图的条款没有提到离线，属于灰区。因此底图（Protomaps / OSM，ODbL）、DEM（Mapterhorn）和由 DEM 生成的等高线，都在开发者本机每季度生成一次 PMTiles 静态文件，放在阿里云 OSS 香港。服务端不运行 Martin / TileServer 这类瓦片服务器。用户下载离线包时，由 Go 后端用 `go-pmtiles` 按范围从 OSS 上的大文件裁出小包并缓存；App 用 `pmtiles://file://` 直接读取本地文件。

之所以这样做：个人开发者买的是低内存的香港 VPS，负担不起瓦片服务器的运维和内存；静态文件几乎零运维；而且 OSM 数据的许可证明确允许离线分发。

## Consequences

- 只有"地形"底图能离线。卫星图（天地图）和"标准"底图（天地图矢量 / OpenFreeMap）只能在线。
- 随用户数增长的成本主要是 OSS 出流量，所以需要限额：单个离线包约 ≤ 100 × 100 km，每台设备每天 ≤ 1 GB。
- CJK 字形（约 34 MB）必须自备，因为 Protomaps 官方字形的 CJK 区段是空的。
