# 只读前 5 页，并说明有没有读完

站点：`GET /big?page=<页码>`，页码从 1 开始，返回

```json
{"rows":[{"id":"B1a"},{"id":"B1b"}],"has_more":true}
```

`has_more` 是 `false` 时是最后一页。这个列表很长（几十页），一次调用最多只允许读 5 页。

写一个没有入参的能力，输出：

- `count`（整数）：读到的行数。
- `truncated`（布尔）：读满 5 页时还有更多页，就是 `true`；否则 `false`。

请求不能超过 5 次。

## 通用要求

- 只交一份完整的 `conch.yaml`，不要解释。
- `conch: quiz`，`driver: http`，`base_url: http://127.0.0.1:18080`，能力名固定为 `run`。
- 入参名和输出字段名按题面写，不要改名，不要多包一层。
- 除非题面写了要登录，否则 `credentials: {}`，不写 `auth`。
- 只能依据 Hermit 的规则说明（`hermit://authoring/guide`）来写。
