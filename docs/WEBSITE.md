# OneTunnel 官网

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

## 自动发布

推送 `main` 分支的网站、构建脚本、Logo 或最低安卓版本修改，会自动部署。
也可在 Actions 中手动运行 **Deploy OneTunnel website**。
仓库 Settings → Pages 的发布来源使用 **GitHub Actions**。

工作流只上传 `_site`，不上传安卓配置、构建工具或本地 APK；保持仓库现有 Pages 域名设置。
规范网址由 `configure-pages` 的输出注入，资源均为相对路径，支持项目子目录。
Action 版本已与 GitHub 官方仓库发布记录核对。

页面的 GitHub 按钮指向公开源码仓库，未伪装成安装包下载；后续发布 Release 时可增加下载入口。
