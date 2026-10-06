# 星径 Stars Trail

面向户外徒步者的无广告地图应用：标注地点、导入轨迹、临时组队共享位置、查看周边路线与天气，地形底图可下载离线包，没信号也能用。主要服务中国大陆用户，同时支持规划海外经典线路。

目前处于内测阶段，暂未上架应用商店。安装包在 [Releases](https://github.com/zibyn/stars-trail/releases) 下载，App 会提示新版本。

## 周边路网怎么用

1. **打开**：右上"图层" →"周边路网"。打开后显示：
   - **山路高亮**：底图小径加粗，橙色。
   - **徒步线路**：洋红色。
   - **公开轨迹**：细的半透明紫线，重叠越多颜色越深，z11 起显示；有网时读服务器瓦片，离线时读离线包里的快照，两者不会同时画。
2. **经过这里的轨迹**：开关打开时点地图，底部列出附近经过的线路（名称、类型、长度），附近没有时不弹出。每条可以：
   - **设为参考轨迹**：先存成计划轨迹，再设为参考轨迹。
   - **保存到我的轨迹**：存成计划轨迹。
3. **离线**：先在有网时下载离线包，包里会带上该区域的徒步线路和公开轨迹快照。离线时列表会提示"离线中：公开轨迹来自离线包快照"。
4. **关于**：菜单 →"关于"，列出数据来源和许可证，并链接到 OSM 抽取脚本。

## 数据来源

- 底图：© OpenStreetMap 贡献者（ODbL），Protomaps
- 高程与等高线：Mapterhorn、Copernicus GLO-30
- 徒步线路：从 OpenStreetMap 抽取，规则即 [`scripts/osm-extract.sh`](scripts/osm-extract.sh)
- 卫星图与标准底图（仅在线）：天地图
- 地名搜索：离线地名索引、OpenStreetMap（Photon）、天地图
- 天气：Open-Meteo（预报）、和风天气（官方预警）

App 内"关于"页有完整的来源与许可证。

## 开发

构建、离线数据的生成与上传见 [docs/development.md](docs/development.md)；服务端部署见 [deploy/README.md](deploy/README.md)；参与开发和发版见 [CONTRIBUTING.md](CONTRIBUTING.md)，安全问题见 [SECURITY.md](SECURITY.md)。

## 许可

代码以 [GPL-3.0](LICENSE) 发布。地图与天气数据按各自的许可使用，见上面的「数据来源」。
