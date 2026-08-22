# web-demo

一个极简的 **HTML + PHP** 演示项目：前端页面通过 `fetch` 调用 PHP 接口获取数据并渲染展示，用于快速查看「前端 → 后端接口 → 页面渲染」的完整链路效果。

## 运行方式

```bash
# 进入项目目录
cd web-demo
# 启动 PHP 内置服务器
php -S 0.0.0.0:8000 -t .
```

浏览器访问：

- 页面：<http://localhost:8000/>
- 接口：<http://localhost:8000/api.php>

## 文件说明

| 文件 | 作用 |
|------|------|
| `index.html` | 前端页面：加载时 `fetch` 调 `api.php`，把返回数据渲染为卡片/表格 |
| `api.php` | 后端接口：返回一份演示用的 JSON 数据（用户/记录列表），支持可选过滤参数 |

## 接口约定

`GET /api.php`

- 无参数：返回全部演示数据。
- 可选参数 `type=active`：仅返回状态为「启用」的记录（演示参数校验与过滤）。

响应格式：

```json
{
  "ok": true,
  "total": 6,
  "data": [ { "id": 1, "name": "张三", "role": "前端", "status": "启用" } ]
}
```

## 技术栈

- 前端：原生 HTML / CSS / JavaScript，零依赖。
- 后端：PHP 8+ 纯脚本接口，无框架、无数据库（数据内置于接口做演示）。

## 纪律

AI 协同开发请遵循 [AGENTS.md](./AGENTS.md)。
