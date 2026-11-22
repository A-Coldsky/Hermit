# 用游标翻页

站点：`GET /feed?cursor=<游标>`。第一次的游标固定是 `start`。返回

```json
{"items":[{"id":"F-start-1"},{"id":"F-start-2"}],"next_cursor":"c2"}
```

下一次请求要带上这次返回的 `next_cursor`。最后一页的 `next_cursor` 是 `null`。游标带错会返回 400。不会超过 30 页。

写一个没有入参的能力，把所有页读完，输出 `ids`（数组），每项只有 `id`，顺序和站点一致。读完最后一页就停。

## 通用要求

- 只交一份完整的 `conch.yaml`，不要解释。
- `conch: quiz`，`driver: http`，`base_url: http://127.0.0.1:18080`，能力名固定为 `run`。
- 入参名和输出字段名按题面写，不要改名，不要多包一层。
- 除非题面写了要登录，否则 `credentials: {}`，不写 `auth`。
- 只能依据 Hermit 的规则说明（`hermit://authoring/guide`）来写。
