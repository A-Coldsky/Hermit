# 读一个 JSON 字段

站点：`GET /api/ping` 返回 `{"state":"ok","node":"n1"}`。

写一个没有入参的能力，输出 `state`（字符串），值是响应里的 `state`。

## 通用要求

- 只交一份完整的 `conch.yaml`，不要解释。
- `conch: quiz`，`driver: http`，`base_url: http://127.0.0.1:18080`，能力名固定为 `run`。
- 入参名和输出字段名按题面写，不要改名，不要多包一层。
- 除非题面写了要登录，否则 `credentials: {}`，不写 `auth`。
- 只能依据 Hermit 的规则说明（`hermit://conches/hermit_dev/guide`）来写。
