# OneTunnel 官网

公开网址：[OneTunnel](https://0use.net/OneTunnel/)。

网站源码位于 `website/`，GitHub Actions 配置位于 `.github/workflows/pages.yml`。
单页静态网站，没有第三方字体、追踪或运行时依赖。正文和界面文字均至少 18px，提供系统明暗主题和手动切换。

## 本地预览

需要 Python 3.12 或更高版本：

```sh
python scripts/build-site.py
python -m http.server 8000 --bind 127.0.0.1 --directory _site
```

浏览器访问 `http://127.0.0.1:8000/`。
构建脚本检查站内资源、锚点、图片替代文本与主标题，并从安卓矢量图标生成网站猫形 Logo。

## 界面截图

首页精简为一句介绍、GitHub 入口、截图切换和页脚，不再使用模拟连接卡片或重复的功能 / 使用步骤区。
“连接、代理组、分流规则、端口转发”对应当前 Android 界面的实机截图，明暗切换同步显示相应截图；可点击图片查看原图，也支持左右方向键、Home / End 切换。

八张无损 WebP 位于 `website/assets/screenshots/`，总计约 1.7 MB，按需切换加载。
使用独立的示例实例截图，未读取用户订阅和身份、未启动 VPN，也没有伪造测速值。详见 [截图来源](../website/assets/screenshots/README.md)。

## 自动发布

推送 `main` 分支的网站、构建脚本、Logo 或最低安卓版本修改，会自动部署。
也可在 Actions 中手动运行 **Deploy OneTunnel website**。
仓库 Settings → Pages 的发布来源使用 **GitHub Actions**。

工作流只上传 `_site`，不上传安卓配置、构建工具或本地 APK；保持仓库现有 Pages 域名设置。
规范网址由 `configure-pages` 的输出注入，资源均为相对路径，支持项目子目录。
Action 版本已与 GitHub 官方仓库发布记录核对。

页面的 GitHub 按钮指向公开源码仓库，未伪装成安装包下载；后续发布 Release 时可增加下载入口。

## 发布验证

2026-11-19 首次自动部署成功：[Actions 记录](https://github.com/0use-TE/OneTunnel/actions/runs/44663711437)。
公开首页、样式、脚本及 Logo 均返回 HTTP 200。沿用账户已有的 0use.net 域名，没有改动 DNS 或其他项目。
本地 Chromium 检查了 1440 / 768 / 390 / 320 像素宽度、系统明暗主题、手动切换与持久化、键盘焦点；无横向溢出、加载错误或脚本异常，文字最小 18px。
截图版另验证全部八张图片加载、四个标签的选中状态、键盘导航、主题切换保留当前页面和原图链接。
