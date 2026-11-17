# 扩展与实验性设置

> 入口：mod-tools 设置 → **Extensions** / 对应开关

## Override Scene（覆盖场景）

开启后会替换 Arc 的原始 `Scene`，用来捕获渲染阶段的异常。

> 实验性功能：替换场景会改变事件分发和绘制路径，可能带来兼容性问题；排查渲染问题时再开，平时建议关闭。

## HTTP Redirect（HTTP 重定向）

把指定 URL 前缀重定向到另一个地址，常用于把 GitHub 等站点转到可访问的镜像。

- 开关在 mod-tools 设置里，标注需要重启；
- 配置文件默认是数据目录下的 `http_redirect.properties`（设置项提示里会显示实际路径）；
- 修改配置后重启游戏生效。

> 实验性功能：只建议在开发/网络受限环境使用；重定向会影响游戏内所有走该 URL 的请求。

## 其它实验性开关

mod-tools 设置里还有一些默认关闭的开关，例如：

- **Android input fix**：Android 端输入修复；
- **capture exceptions / capture locals**：配合 Profiler 捕获异常与局部变量；
- **retransform loaded**：热重载时对已加载类做 retransform。

这些开关视使用场景开启；不确定时保持默认关闭。
