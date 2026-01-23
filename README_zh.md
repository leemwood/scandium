# 钪 (Scandium)

Scandium 是一个针对 Minecraft Fabric 1.21.11 的高性能渲染优化模组，旨在与 Sodium 无缝协作。它通过实现先进的剔除技术（如视场剔除和激进的山体剔除）来显著提高帧率，特别是在复杂的地理环境中。

## 功能特性

- **高级剔除**：包含基于视场 (FOV) 的剔除和针对山体的高强度剔除逻辑，减少需要渲染的区块数量。
- **Sodium 集成**：配置选项直接集成在 Sodium 的“视频设置”界面中，无需安装额外的配置菜单模组（如 ModMenu）。
- **性能导向**：极低的系统开销，对渲染效率有显著提升。

## 安装步骤

1. 确保已安装 [Fabric Loader](https://fabricmc.net/)。
2. 下载并安装 [Sodium](https://modrinth.com/mod/sodium)。
3. 将 Scandium 的 jar 文件放入 `.minecraft/mods` 文件夹中。

## 配置说明

你可以在 **选项 > 视频设置 > 钪** 中找到相关的配置项。

## 开源协议

本项目采用 MIT 协议进行授权。
